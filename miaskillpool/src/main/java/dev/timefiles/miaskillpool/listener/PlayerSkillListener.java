package dev.timefiles.miaskillpool.listener;

import dev.timefiles.miaskillpool.MiaSkillpoolPlugin;
import dev.timefiles.miaskillpool.config.SkillDefinition;
import dev.timefiles.miaskillpool.config.SkillRegistry;
import dev.timefiles.miaskillpool.data.PlayerDataStore;
import dev.timefiles.miaskillpool.data.PlayerSkillData;
import dev.timefiles.miaskillpool.gui.AdminSkillPoolGui;
import dev.timefiles.miaskillpool.gui.RandomSkillRollGui;
import dev.timefiles.miaskillpool.gui.SkillPoolGui;
import dev.timefiles.miaskillpool.gui.SkillPoolHolder;
import dev.timefiles.miaskillpool.runtime.RuntimeState;
import dev.timefiles.miaskillpool.runtime.SkillCastService;
import dev.timefiles.miaskillpool.util.Texts;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

public final class PlayerSkillListener implements Listener {
    private static final int PARK_SLOT = 8;

    private final MiaSkillpoolPlugin plugin;
    private final SkillRegistry skillRegistry;
    private final PlayerDataStore dataStore;
    private final RuntimeState runtimeState;
    private final SkillCastService castService;
    private final SkillPoolGui gui;
    private final RandomSkillRollGui randomGui;
    private final AdminSkillPoolGui adminGui;

    public PlayerSkillListener(MiaSkillpoolPlugin plugin, SkillRegistry skillRegistry, PlayerDataStore dataStore, RuntimeState runtimeState, SkillCastService castService, SkillPoolGui gui, RandomSkillRollGui randomGui, AdminSkillPoolGui adminGui) {
        this.plugin = plugin;
        this.skillRegistry = skillRegistry;
        this.dataStore = dataStore;
        this.runtimeState = runtimeState;
        this.castService = castService;
        this.gui = gui;
        this.randomGui = randomGui;
        this.adminGui = adminGui;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        gui.handleClick(event);
        randomGui.handleClick(event);
        adminGui.handleClick(event);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SkillPoolHolder) {
            event.setCancelled(true);
        }
        randomGui.handleDrag(event);
        adminGui.handleDrag(event);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        // Admin GUI search/rename prompts capture the next chat message as text input.
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (adminGui.handleChatInput(event.getPlayer(), text)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        gui.handleClose(event);
        randomGui.handleClose(event);
        adminGui.handleClose(event);
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("miaskillpool.use")) {
            return;
        }

        event.setCancelled(true);
        boolean casting = runtimeState.toggleCasting(player);
        if (casting) {
            // Park on a non-skill hotbar slot (index 8) so every 1-5 press is a real slot
            // change and fires PlayerItemHeldEvent, even the slot the player started on.
            runtimeState.rememberParkedSlot(player, player.getInventory().getHeldItemSlot());
            player.getInventory().setHeldItemSlot(PARK_SLOT);
            player.sendMessage(Texts.PREFIX + Texts.color("&a进入施法模式：&7按 &f1-5 &7释放对应槽位技能，再次按 &fF &7退出。"));
            castService.renderCastingActionbar(player);
        } else {
            player.getInventory().setHeldItemSlot(runtimeState.takeParkedSlot(player));
            player.sendMessage(Texts.PREFIX + Texts.color("&7已退出施法模式。"));
            player.sendActionBar(Component.empty());
        }
    }

    @EventHandler
    public void onSlotKey(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!runtimeState.isCasting(player)) {
            return;
        }
        if (!player.hasPermission("miaskillpool.use")) {
            return;
        }

        // While casting the hotbar is locked; keys 1-5 cast the matching slot.
        event.setCancelled(true);
        int slotIndex = event.getNewSlot();
        if (slotIndex >= 0 && slotIndex < PlayerSkillData.SLOT_COUNT) {
            castService.castEquipped(player, slotIndex);
            castService.renderCastingActionbar(player);
        }
    }

    @EventHandler
    public void onUseSkillBook(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        String skillId = gui.skillId(item);
        if (skillId == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        SkillDefinition skill = skillRegistry.get(skillId).orElse(null);
        if (skill == null) {
            player.sendMessage(Texts.PREFIX + Texts.color("&c技能书对应的技能配置不存在：" + skillId));
            return;
        }

        PlayerSkillData data = dataStore.get(player);
        if (!data.learn(skill.id())) {
            player.sendMessage(Texts.PREFIX + Texts.color("&7你已经学过 " + skill.displayName() + "&7。"));
            return;
        }

        decrementMainHand(player);
        dataStore.save(data);
        player.sendMessage(Texts.PREFIX + Texts.color("&a你学会了 " + skill.displayName() + "&a。"));
    }

    @EventHandler
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player defender) {
            runtimeState.addRage(defender, skillRegistry.rageGainOnTakeDamage());
            runtimeState.enterCombat(defender);
        }

        Player attacker = attacker(event.getDamager());
        if (attacker != null) {
            runtimeState.addRage(attacker, skillRegistry.rageGainOnDealDamage());
            runtimeState.enterCombat(attacker);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runtimeState.setCasting(event.getPlayer(), false);
        adminGui.clearPrompt(event.getPlayer());
        dataStore.save(dataStore.get(event.getPlayer()));
    }

    private Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private void decrementMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        item.setAmount(item.getAmount() - 1);
    }
}
