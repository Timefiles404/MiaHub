package dev.timefiles.miasmartgiftroll.kit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

public class KitSerializer {
    /*
     * Enabled aggressive exception aggregation
     */
    public static String serializeItem(ItemStack item) {
        if (item == null) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();){
            String string;
            try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream((OutputStream)outputStream);){
                dataOutput.writeObject(item);
                string = Base64Coder.encodeLines((byte[])outputStream.toByteArray());
            }
            return string;
        }
        catch (IOException e) {
            throw new RuntimeException("Unable to serialize item", e);
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    public static ItemStack deserializeItem(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64));){
            ItemStack itemStack;
            try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream((InputStream)inputStream);){
                itemStack = (ItemStack)dataInput.readObject();
            }
            return itemStack;
        }
        catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Unable to deserialize item", e);
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    public static String serializeItems(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();){
            String string;
            try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream((OutputStream)outputStream);){
                dataOutput.writeInt(items.size());
                for (ItemStack item : items) {
                    dataOutput.writeObject(item);
                }
                string = Base64Coder.encodeLines((byte[])outputStream.toByteArray());
            }
            return string;
        }
        catch (IOException e) {
            throw new RuntimeException("Unable to serialize items", e);
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    public static List<ItemStack> deserializeItems(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new ArrayList<ItemStack>();
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64));){
            ArrayList<ItemStack> arrayList;
            try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream((InputStream)inputStream);){
                int size = dataInput.readInt();
                ArrayList<ItemStack> items = new ArrayList<ItemStack>(size);
                for (int i = 0; i < size; ++i) {
                    items.add((ItemStack)dataInput.readObject());
                }
                arrayList = items;
            }
            return arrayList;
        }
        catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Unable to deserialize items", e);
        }
    }
}



