#!/usr/bin/env python3
import argparse
import base64
import datetime as dt
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import shutil
import sys
import tempfile
import time
import urllib.parse
import zipfile
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


HASH_ALGORITHM = "pbkdf2_sha256"
HASH_ITERATIONS = 210_000
SESSION_COOKIE = "plug_session"


def now_iso():
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()


def b64url(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def unb64url(value):
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def hash_secret(secret, salt=None):
    salt = salt or secrets.token_bytes(18)
    digest = hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, HASH_ITERATIONS)
    return f"{HASH_ALGORITHM}${HASH_ITERATIONS}${b64url(salt)}${b64url(digest)}"


def verify_secret(secret, encoded):
    try:
        algorithm, iterations, salt, expected = encoded.split("$", 3)
        if algorithm != HASH_ALGORITHM:
            return False
        digest = hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), unb64url(salt), int(iterations))
        return hmac.compare_digest(b64url(digest), expected)
    except Exception:
        return False


def safe_segment(value):
    value = value or "artifact"
    value = value.replace("\\", "_").replace("/", "_").replace("..", "_")
    return re.sub(r"[^A-Za-z0-9._@+-]+", "_", value).strip("._") or "artifact"


def normalize_plugin_name(value):
    normalized = re.sub(r"[^A-Za-z0-9]+", "", value or "").lower()
    if not normalized:
        raise ValueError("plugin name is empty")
    return normalized


def parse_scalar(value):
    value = value.strip()
    if not value:
        return ""
    if (value.startswith('"') and value.endswith('"')) or (value.startswith("'") and value.endswith("'")):
        return value[1:-1]
    return value


def parse_list_value(value):
    value = value.strip()
    if not value:
        return []
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        if not inner:
            return []
        return [parse_scalar(part.strip()) for part in inner.split(",") if part.strip()]
    return [parse_scalar(value)]


def parse_plugin_yml(text):
    result = {
        "pluginName": "",
        "version": "",
        "dependencies": [],
        "softDependencies": [],
        "loadBefore": [],
    }
    key_map = {
        "name": "pluginName",
        "version": "version",
        "depend": "dependencies",
        "softdepend": "softDependencies",
        "loadbefore": "loadBefore",
    }
    lines = text.splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        if not line.strip() or line.lstrip().startswith("#") or line[:1].isspace():
            index += 1
            continue
        match = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", line)
        if not match:
            index += 1
            continue
        raw_key, raw_value = match.groups()
        key = key_map.get(raw_key.lower())
        if key is None:
            index += 1
            continue
        if key in {"pluginName", "version"}:
            result[key] = parse_scalar(raw_value)
            index += 1
            continue
        values = parse_list_value(raw_value)
        cursor = index + 1
        while cursor < len(lines):
            child = lines[cursor]
            if not child.startswith((" ", "\t")) and not child.startswith("- "):
                break
            item = child.strip()
            if item.startswith("- "):
                values.append(parse_scalar(item[2:]))
            cursor += 1
        result[key] = [item for item in values if item]
        index = cursor
    return result


def indentation(line):
    return len(line) - len(line.lstrip(" "))


def parse_paper_plugin_yml(text):
    result = parse_plugin_yml(text)
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.strip() != "server:":
            continue
        server_indent = indentation(line)
        cursor = index + 1
        while cursor < len(lines):
            line = lines[cursor]
            if not line.strip():
                cursor += 1
                continue
            current_indent = indentation(line)
            if current_indent <= server_indent:
                break
            plugin_match = re.match(r"^\s{2,}([A-Za-z0-9_.-]+):\s*$", line)
            if not plugin_match:
                cursor += 1
                continue
            plugin_name = plugin_match.group(1)
            plugin_indent = indentation(line)
            block = []
            cursor += 1
            while cursor < len(lines):
                child = lines[cursor]
                if child.strip() and indentation(child) <= plugin_indent:
                    break
                block.append(child.strip())
                cursor += 1
            required = any(item.lower() == "required: true" for item in block)
            target = result["dependencies"] if required else result["softDependencies"]
            if plugin_name not in target:
                target.append(plugin_name)
    return result


def merge_metadata(primary, secondary):
    merged = dict(primary)
    for key in ("pluginName", "version"):
        if not merged.get(key) and secondary.get(key):
            merged[key] = secondary[key]
    for key in ("dependencies", "softDependencies", "loadBefore"):
        values = list(merged.get(key) or [])
        for item in secondary.get(key) or []:
            if item not in values:
                values.append(item)
        merged[key] = values
    return merged


def inspect_jar(path):
    metadata = {
        "pluginName": "",
        "version": "",
        "dependencies": [],
        "softDependencies": [],
        "loadBefore": [],
        "hasPluginYml": False,
        "hasPaperPluginYml": False,
        "restartRequired": False,
    }
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        if "plugin.yml" in names:
            metadata["hasPluginYml"] = True
            with jar.open("plugin.yml") as stream:
                text = stream.read().decode("utf-8", errors="replace")
            metadata = merge_metadata(metadata, parse_plugin_yml(text))
        if "paper-plugin.yml" in names:
            metadata["hasPaperPluginYml"] = True
            metadata["restartRequired"] = True
            with jar.open("paper-plugin.yml") as stream:
                text = stream.read().decode("utf-8", errors="replace")
            metadata = merge_metadata(metadata, parse_paper_plugin_yml(text))
    return metadata


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_disposition(value):
    result = {}
    for part in value.split(";"):
        part = part.strip()
        if "=" not in part:
            continue
        key, raw = part.split("=", 1)
        raw = raw.strip()
        if raw.startswith('"') and raw.endswith('"'):
            raw = raw[1:-1]
        result[key.lower()] = raw
    return result


def parse_multipart(content_type, body):
    match = re.search(r"boundary=(?P<boundary>[^;]+)", content_type)
    if not match:
        raise ValueError("multipart boundary is missing")
    boundary = match.group("boundary").strip().strip('"').encode("utf-8")
    fields = {}
    files = {}
    for part in body.split(b"--" + boundary):
        part = part.strip()
        if not part or part == b"--":
            continue
        if part.endswith(b"--"):
            part = part[:-2].strip()
        if b"\r\n\r\n" in part:
            raw_headers, data = part.split(b"\r\n\r\n", 1)
            line_separator = b"\r\n"
        elif b"\n\n" in part:
            raw_headers, data = part.split(b"\n\n", 1)
            line_separator = b"\n"
        else:
            continue
        headers = {}
        for raw_line in raw_headers.split(line_separator):
            if b":" not in raw_line:
                continue
            key, value = raw_line.split(b":", 1)
            headers[key.decode("utf-8", errors="replace").lower()] = value.decode("utf-8", errors="replace").strip()
        disposition = parse_disposition(headers.get("content-disposition", ""))
        name = disposition.get("name")
        if not name:
            continue
        if data.endswith(b"\r\n"):
            data = data[:-2]
        elif data.endswith(b"\n"):
            data = data[:-1]
        filename = disposition.get("filename")
        if filename is not None:
            files[name] = {"filename": filename, "data": data}
        else:
            fields[name] = data.decode("utf-8", errors="replace").strip()
    return fields, files


class PlugStore:
    def __init__(self, root, public_base_url):
        self.root = Path(root)
        self.public_base_url = public_base_url.rstrip("/")
        self.artifacts = self.root / "artifacts"
        self.registry_path = self.root / "registry.json"
        self.root.mkdir(parents=True, exist_ok=True)
        self.artifacts.mkdir(parents=True, exist_ok=True)

    def load(self):
        if self.registry_path.is_file():
            with self.registry_path.open("r", encoding="utf-8") as handle:
                return self.normalize_registry(json.load(handle))
        return {"schema": 1, "generatedBy": "MiaHub PlugSite", "updatedAt": now_iso(), "plugins": {}, "dependencies": {}}

    def normalize_registry(self, registry):
        registry.setdefault("plugins", {})
        registry.setdefault("dependencies", {})

        plugins = {}
        for key, entry in registry.get("plugins", {}).items():
            if not isinstance(entry, dict):
                continue
            plugin_name = entry.get("pluginName") or entry.get("name") or key
            entry["pluginName"] = plugin_name
            entry["normalizedPluginName"] = normalize_plugin_name(plugin_name)
            entry["id"] = safe_segment(entry.get("id") or key).lower()
            entry["version"] = str(entry.get("version") or entry.get("pluginVersion") or "latest")
            entry["pluginVersion"] = str(entry.get("pluginVersion") or entry["version"])
            entry.setdefault("dependencies", [])
            entry.setdefault("softDependencies", [])
            entry.setdefault("loadBefore", [])
            plugins[entry["id"]] = entry

        dependencies = {}
        for key, entry in registry.get("dependencies", {}).items():
            if not isinstance(entry, dict):
                continue
            plugin_name = entry.get("pluginName") or key
            normalized = normalize_plugin_name(plugin_name)
            entry["pluginName"] = plugin_name
            entry["normalizedName"] = normalized
            entry["version"] = str(entry.get("version") or entry.get("pluginVersion") or "latest")
            entry["pluginVersion"] = str(entry.get("pluginVersion") or entry["version"])
            entry["fileName"] = plugin_name + ".jar"
            entry.setdefault("dependencies", [])
            entry.setdefault("softDependencies", [])
            entry.setdefault("loadBefore", [])
            entry.setdefault("autoInstall", True)
            dependencies[normalized] = entry

        registry["plugins"] = plugins
        registry["dependencies"] = dependencies
        return registry

    def save(self, registry):
        registry["updatedAt"] = now_iso()
        tmp = self.registry_path.with_suffix(".tmp")
        with tmp.open("w", encoding="utf-8") as handle:
            json.dump(registry, handle, ensure_ascii=False, indent=2)
        tmp.replace(self.registry_path)

    def register_artifact(self, kind, temp_path, upload_name, fields):
        metadata = inspect_jar(temp_path)
        if not metadata.get("pluginName"):
            raise ValueError("jar does not contain a plugin name in plugin.yml or paper-plugin.yml")

        artifact_name = safe_segment(upload_name or Path(temp_path).name)
        digest = sha256_file(temp_path)
        size = Path(temp_path).stat().st_size
        registry = self.load()

        if kind == "mia":
            module = safe_segment(fields.get("module") or metadata["pluginName"]).lower()
            version = safe_segment(fields.get("version") or metadata.get("version") or "latest")
            target_dir = self.artifacts / "mia" / module / version
            target_dir.mkdir(parents=True, exist_ok=True)
            target = target_dir / artifact_name
            shutil.move(str(temp_path), target)
            entry = {
                "id": module,
                "pluginName": metadata["pluginName"],
                "normalizedPluginName": normalize_plugin_name(metadata["pluginName"]),
                "version": version,
                "pluginVersion": metadata.get("version") or version,
                "fileName": fields.get("fileName") or metadata["pluginName"] + ".jar",
                "artifact": artifact_name,
                "releaseTag": fields.get("releaseTag") or "",
                "repository": fields.get("repository") or "",
                "sha256": digest,
                "size": size,
                "downloadUrl": f"{self.public_base_url}/api/download/mia/{urllib.parse.quote(module)}/{urllib.parse.quote(version)}/{urllib.parse.quote(artifact_name)}",
                "dependencies": metadata.get("dependencies") or [],
                "softDependencies": metadata.get("softDependencies") or [],
                "loadBefore": metadata.get("loadBefore") or [],
                "restartRequired": bool(metadata.get("restartRequired")),
                "uploadedAt": now_iso(),
            }
            registry.setdefault("plugins", {})[module] = entry
        elif kind == "dependency":
            plugin_name = metadata["pluginName"]
            key = normalize_plugin_name(plugin_name)
            version = safe_segment(metadata.get("version") or fields.get("version") or "latest")
            target_dir = self.artifacts / "dependencies" / safe_segment(plugin_name) / version
            target_dir.mkdir(parents=True, exist_ok=True)
            target = target_dir / artifact_name
            shutil.move(str(temp_path), target)
            entry = {
                "pluginName": plugin_name,
                "normalizedName": key,
                "version": version,
                "pluginVersion": metadata.get("version") or version,
                "fileName": fields.get("fileName") or plugin_name + ".jar",
                "artifact": artifact_name,
                "sha256": digest,
                "size": size,
                "downloadUrl": f"{self.public_base_url}/api/download/dependencies/{urllib.parse.quote(plugin_name)}/{urllib.parse.quote(version)}/{urllib.parse.quote(artifact_name)}",
                "dependencies": metadata.get("dependencies") or [],
                "softDependencies": metadata.get("softDependencies") or [],
                "loadBefore": metadata.get("loadBefore") or [],
                "restartRequired": bool(metadata.get("restartRequired")),
                "autoInstall": True,
                "uploadedAt": now_iso(),
            }
            registry.setdefault("dependencies", {})[key] = entry
        else:
            raise ValueError("kind must be mia or dependency")

        self.save(registry)
        return entry

    def find_dependency(self, plugin_name):
        registry = self.load()
        return registry.get("dependencies", {}).get(normalize_plugin_name(plugin_name))

    def artifact_path(self, kind, name, version, artifact):
        if kind == "mia":
            return self.artifacts / "mia" / safe_segment(name).lower() / safe_segment(version) / safe_segment(artifact)
        return self.artifacts / "dependencies" / safe_segment(name) / safe_segment(version) / safe_segment(artifact)


class PlugHandler(BaseHTTPRequestHandler):
    server_version = "MiaHubPlugSite/1.0"

    def do_HEAD(self):
        route = urllib.parse.urlparse(self.path)
        if route.path == "/" or route.path == "/api/registry":
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8" if route.path == "/" else "application/json; charset=utf-8")
            self.end_headers()
            return
        return self.not_found()

    def do_GET(self):
        route = urllib.parse.urlparse(self.path)
        path = route.path
        if path == "/":
            return self.dashboard() if self.current_user() else self.login_page()
        if path == "/logout":
            return self.logout()
        if path == "/api/registry":
            return self.json_response(self.store.load())
        if path.startswith("/api/dependencies/"):
            name = urllib.parse.unquote(path.removeprefix("/api/dependencies/"))
            entry = self.store.find_dependency(name)
            if not entry:
                return self.error_json(HTTPStatus.NOT_FOUND, "dependency not found")
            return self.json_response(entry)
        if path.startswith("/api/download/mia/"):
            return self.download_artifact(path, "mia", protected=False)
        if path.startswith("/api/download/dependencies/"):
            return self.download_artifact(path, "dependencies", protected=True)
        return self.not_found()

    def do_POST(self):
        route = urllib.parse.urlparse(self.path)
        if route.path == "/login":
            return self.login()
        if route.path == "/upload":
            if not self.current_user():
                return self.redirect("/")
            return self.handle_upload(require_token=False)
        if route.path == "/api/upload":
            if not self.valid_token("uploadTokenHash"):
                return self.error_json(HTTPStatus.UNAUTHORIZED, "invalid upload token")
            return self.handle_upload(require_token=True)
        return self.not_found()

    @property
    def config(self):
        return self.server.config

    @property
    def store(self):
        return self.server.store

    def current_user(self):
        cookie = SimpleCookie(self.headers.get("Cookie", ""))
        morsel = cookie.get(SESSION_COOKIE)
        if not morsel:
            return ""
        try:
            payload, signature = morsel.value.rsplit(".", 1)
            expected = hmac.new(self.config["sessionSecret"].encode("utf-8"), payload.encode("ascii"), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(signature, expected):
                return ""
            data = json.loads(unb64url(payload).decode("utf-8"))
            if data.get("exp", 0) < int(time.time()):
                return ""
            return data.get("user", "")
        except Exception:
            return ""

    def valid_token(self, key):
        header = self.headers.get("Authorization", "")
        token = ""
        if header.lower().startswith("bearer "):
            token = header[7:].strip()
        if not token:
            token = self.headers.get("X-Plug-Token", "").strip()
        encoded = self.config.get(key, "")
        return bool(token and encoded and verify_secret(token, encoded))

    def login_page(self, error=""):
        body = f"""
        <main class="login">
          <form method="post" action="/login" class="panel">
            <h1>MiaHub Plug</h1>
            <p>登录后上传依赖 jar，查看 Mia 插件镜像状态。</p>
            {'<div class="error">' + html.escape(error) + '</div>' if error else ''}
            <label>账号<input name="username" autocomplete="username" required></label>
            <label>密码<input name="password" type="password" autocomplete="current-password" required></label>
            <button type="submit">登录</button>
          </form>
        </main>
        """
        return self.html_response("MiaHub Plug 登录", body)

    def dashboard(self):
        registry = self.store.load()
        plugins = sorted(registry.get("plugins", {}).values(), key=lambda item: item.get("id", ""))
        dependencies = sorted(registry.get("dependencies", {}).values(), key=lambda item: item.get("pluginName", "").lower())
        plugin_rows = "\n".join(self.artifact_row(item, item.get("id", "")) for item in plugins) or "<tr><td colspan='6'>暂无 Mia 插件镜像。</td></tr>"
        dependency_rows = "\n".join(self.artifact_row(item, item.get("pluginName", "")) for item in dependencies) or "<tr><td colspan='6'>暂无依赖插件。</td></tr>"
        body = f"""
        <header>
          <div>
            <h1>MiaHub Plug</h1>
            <p>更新时间：{html.escape(registry.get('updatedAt', '-'))}</p>
          </div>
          <a class="logout" href="/logout">退出</a>
        </header>
        <section class="upload">
          <form method="post" action="/upload" enctype="multipart/form-data" class="panel">
            <h2>上传 jar</h2>
            <div class="grid">
              <label>类型
                <select name="kind">
                  <option value="dependency">依赖插件</option>
                  <option value="mia">Mia 插件</option>
                </select>
              </label>
              <label>Mia 模块 id<input name="module" placeholder="例如 miaskillpool"></label>
              <label>版本<input name="version" placeholder="留空则读取 plugin.yml"></label>
              <label>安装文件名<input name="fileName" placeholder="例如 MythicMobs.jar"></label>
            </div>
            <input type="file" name="artifact" accept=".jar" required>
            <button type="submit">上传并解析</button>
          </form>
        </section>
        <section>
          <h2>Mia 插件</h2>
          <table>
            <thead><tr><th>插件</th><th>版本</th><th>文件</th><th>硬依赖</th><th>SHA-256</th><th>时间</th></tr></thead>
            <tbody>{plugin_rows}</tbody>
          </table>
        </section>
        <section>
          <h2>依赖插件</h2>
          <table>
            <thead><tr><th>插件</th><th>版本</th><th>文件</th><th>硬依赖</th><th>SHA-256</th><th>时间</th></tr></thead>
            <tbody>{dependency_rows}</tbody>
          </table>
        </section>
        """
        return self.html_response("MiaHub Plug", body)

    def artifact_row(self, item, name):
        dependencies = ", ".join(item.get("dependencies") or []) or "-"
        digest = item.get("sha256", "")
        digest_short = digest[:12] + "..." if len(digest) > 12 else digest
        link = html.escape(item.get("downloadUrl", "#"))
        return f"""
        <tr>
          <td>{html.escape(name)}</td>
          <td>{html.escape(item.get('version', '-'))}</td>
          <td><a href="{link}">{html.escape(item.get('artifact', '-'))}</a></td>
          <td>{html.escape(dependencies)}</td>
          <td title="{html.escape(digest)}"><code>{html.escape(digest_short)}</code></td>
          <td>{html.escape(item.get('uploadedAt', '-'))}</td>
        </tr>
        """

    def login(self):
        length = int(self.headers.get("Content-Length", "0"))
        fields = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"), keep_blank_values=True)
        username = fields.get("username", [""])[0]
        password = fields.get("password", [""])[0]
        if username != self.config.get("adminUser") or not verify_secret(password, self.config.get("adminPasswordHash", "")):
            return self.login_page("账号或密码不正确。")
        payload = b64url(json.dumps({"user": username, "exp": int(time.time()) + 12 * 3600}, separators=(",", ":")).encode("utf-8"))
        signature = hmac.new(self.config["sessionSecret"].encode("utf-8"), payload.encode("ascii"), hashlib.sha256).hexdigest()
        self.send_response(HTTPStatus.SEE_OTHER)
        self.send_header("Location", "/")
        self.send_header("Set-Cookie", f"{SESSION_COOKIE}={payload}.{signature}; HttpOnly; SameSite=Lax; Path=/; Max-Age=43200")
        self.end_headers()

    def logout(self):
        self.send_response(HTTPStatus.SEE_OTHER)
        self.send_header("Location", "/")
        self.send_header("Set-Cookie", f"{SESSION_COOKIE}=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0")
        self.end_headers()

    def handle_upload(self, require_token):
        max_bytes = int(self.config.get("maxUploadBytes", 300 * 1024 * 1024))
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > max_bytes:
            return self.error_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "upload too large")
        fields, files = parse_multipart(self.headers.get("Content-Type", ""), self.rfile.read(length))
        file_item = files.get("artifact")
        if file_item is None or not file_item.get("filename"):
            return self.error_json(HTTPStatus.BAD_REQUEST, "artifact file is required")
        kind = fields.get("kind", "dependency").lower()
        metadata_fields = {name: fields.get(name, "") for name in ("module", "version", "fileName", "releaseTag", "repository")}
        tmp = tempfile.NamedTemporaryFile(prefix="plug-upload-", suffix=".jar", delete=False)
        tmp_path = Path(tmp.name)
        try:
            with tmp:
                tmp.write(file_item["data"])
            entry = self.store.register_artifact(kind, tmp_path, file_item["filename"], metadata_fields)
            if require_token:
                return self.json_response({"ok": True, "artifact": entry})
            return self.redirect("/")
        except Exception as exc:
            tmp_path.unlink(missing_ok=True)
            if require_token:
                return self.error_json(HTTPStatus.BAD_REQUEST, str(exc))
            return self.html_response("上传失败", f"<main class='login'><section class='panel'><h1>上传失败</h1><p>{html.escape(str(exc))}</p><a href='/'>返回</a></section></main>", HTTPStatus.BAD_REQUEST)

    def download_artifact(self, path, kind, protected):
        if protected and not (self.current_user() or self.valid_token("downloadTokenHash")):
            return self.error_json(HTTPStatus.UNAUTHORIZED, "download token required")
        prefix = "/api/download/mia/" if kind == "mia" else "/api/download/dependencies/"
        parts = [urllib.parse.unquote(part) for part in path.removeprefix(prefix).split("/", 2)]
        if len(parts) != 3:
            return self.not_found()
        artifact_path = self.store.artifact_path(kind, parts[0], parts[1], parts[2])
        if not artifact_path.is_file():
            return self.not_found()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/java-archive")
        self.send_header("Content-Length", str(artifact_path.stat().st_size))
        self.send_header("Content-Disposition", f"attachment; filename=\"{artifact_path.name}\"")
        self.end_headers()
        with artifact_path.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile)

    def html_response(self, title, body, status=HTTPStatus.OK):
        css = """
        body{margin:0;background:#f5f7fb;color:#1c2430;font:14px/1.5 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
        header{display:flex;align-items:flex-end;justify-content:space-between;padding:32px 40px 18px;background:#162033;color:white}
        h1,h2,p{margin:0} h1{font-size:28px} h2{font-size:18px;margin:26px 40px 10px}
        header p{color:#aebbd0;margin-top:4px}.logout{color:#c7e8ff}.login{min-height:100vh;display:grid;place-items:center}
        .panel{background:white;border:1px solid #dfe5ef;border-radius:8px;padding:22px;box-shadow:0 10px 24px rgba(28,36,48,.08)}
        .login .panel{width:min(380px,calc(100vw - 48px))}.panel p{color:#5a6575;margin:8px 0 18px}
        label{display:grid;gap:6px;color:#394457;font-weight:600}input,select{height:38px;border:1px solid #cad3df;border-radius:6px;padding:0 10px;font:inherit}
        input[type=file]{height:auto;padding:10px;background:#f8fafc}button{height:40px;border:0;border-radius:6px;background:#155eef;color:white;font-weight:700;padding:0 16px;cursor:pointer}
        form{display:grid;gap:14px}.upload{padding:0 40px}.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
        table{width:calc(100% - 80px);margin:0 40px 26px;border-collapse:collapse;background:white;border:1px solid #dfe5ef;border-radius:8px;overflow:hidden}
        th,td{text-align:left;padding:10px 12px;border-bottom:1px solid #edf1f6;vertical-align:top}th{background:#eef3f8;color:#394457}code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.error{color:#b42318;background:#fff0ee;border:1px solid #ffd1cc;border-radius:6px;padding:8px 10px}
        @media(max-width:760px){header{padding:24px}.upload{padding:0 24px}.grid{grid-template-columns:1fr}table{width:calc(100% - 48px);margin-left:24px;margin-right:24px;display:block;overflow:auto}}
        """
        data = f"<!doctype html><meta charset='utf-8'><title>{html.escape(title)}</title><style>{css}</style>{body}".encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def json_response(self, payload, status=HTTPStatus.OK):
        data = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def error_json(self, status, message):
        return self.json_response({"ok": False, "error": message}, status)

    def redirect(self, target):
        self.send_response(HTTPStatus.SEE_OTHER)
        self.send_header("Location", target)
        self.end_headers()

    def not_found(self):
        return self.error_json(HTTPStatus.NOT_FOUND, "not found")

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))


class PlugServer(ThreadingHTTPServer):
    def __init__(self, address, handler, config, store):
        super().__init__(address, handler)
        self.config = config
        self.store = store


def load_config(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5500)
    parser.add_argument("--config", default="config.json")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--hash-secret")
    args = parser.parse_args()

    if args.hash_secret:
        print(hash_secret(args.hash_secret))
        return

    config = load_config(args.config)
    store = PlugStore(args.data_dir, config.get("publicBaseUrl", f"http://{args.host}:{args.port}"))
    server = PlugServer((args.host, args.port), PlugHandler, config, store)
    print(f"MiaHub PlugSite listening on {args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
