package dev.timefiles.miapickaxe.data;

import dev.timefiles.miapickaxe.MiaPickaxe;
import dev.timefiles.miapickaxe.data.UpgradeType;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public class PickaxeData {
    private static final String NAMESPACE = "miapickaxe";
    public static final NamespacedKey KEY_UUID = new NamespacedKey(MiaPickaxe.getInstance(), "uuid");
    public static final NamespacedKey KEY_MINED = new NamespacedKey(MiaPickaxe.getInstance(), "mined");
    public static final NamespacedKey KEY_LEVEL = new NamespacedKey(MiaPickaxe.getInstance(), "level");
    public static final NamespacedKey KEY_EFFICIENCY = new NamespacedKey(MiaPickaxe.getInstance(), "efficiency");
    public static final NamespacedKey KEY_FORTUNE = new NamespacedKey(MiaPickaxe.getInstance(), "fortune");
    public static final NamespacedKey KEY_UNBREAKING = new NamespacedKey(MiaPickaxe.getInstance(), "unbreaking");
    public static final NamespacedKey KEY_BOUND_TO = new NamespacedKey(MiaPickaxe.getInstance(), "bound_to");
    public static final NamespacedKey KEY_BOUND_NAME = new NamespacedKey(MiaPickaxe.getInstance(), "bound_name");
    public static final NamespacedKey KEY_IS_UNBREAKABLE = new NamespacedKey(MiaPickaxe.getInstance(), "is_unbreakable");
    public static final NamespacedKey KEY_IS_SILK_TOUCH = new NamespacedKey(MiaPickaxe.getInstance(), "is_silk_touch");
    public static final NamespacedKey KEY_HAS_TOGGLE_STONE = new NamespacedKey(MiaPickaxe.getInstance(), "has_toggle_stone");
    private final ItemStack item;
    private final ItemMeta meta;
    private final PersistentDataContainer pdc;

    public PickaxeData(ItemStack item) {
        this.item = item;
        this.meta = item.getItemMeta();
        this.pdc = this.meta.getPersistentDataContainer();
    }

    public static boolean isMiaPickaxe(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(KEY_UUID, PersistentDataType.STRING);
    }

    public void initializeNew() {
        this.setUUID(UUID.randomUUID().toString());
        this.setMined(0L);
        this.setLevel(1);
        this.setEfficiencyLevel(0);
        this.setFortuneLevel(0);
        this.setUnbreakingLevel(0);
    }

    public String getUUID() {
        return this.pdc.get(KEY_UUID, PersistentDataType.STRING);
    }

    public void setUUID(String uuid) {
        this.pdc.set(KEY_UUID, PersistentDataType.STRING, uuid);
        this.saveChanges();
    }

    public long getMined() {
        Long minedLong = (Long)this.pdc.get(KEY_MINED, PersistentDataType.LONG);
        if (minedLong != null) {
            return minedLong;
        }
        Integer minedInt = (Integer)this.pdc.get(KEY_MINED, PersistentDataType.INTEGER);
        return minedInt != null ? (long)minedInt.intValue() : 0L;
    }

    public void setMined(long mined) {
        this.pdc.set(KEY_MINED, PersistentDataType.LONG, mined);
        this.saveChanges();
    }

    public void addMined(long amount) {
        this.setMined(this.getMined() + amount);
    }

    public int getLevel() {
        Integer level = (Integer)this.pdc.get(KEY_LEVEL, PersistentDataType.INTEGER);
        return level != null ? level : 1;
    }

    public void setLevel(int level) {
        this.pdc.set(KEY_LEVEL, PersistentDataType.INTEGER, level);
        this.saveChanges();
    }

    public int getEfficiencyLevel() {
        Integer level = (Integer)this.pdc.get(KEY_EFFICIENCY, PersistentDataType.INTEGER);
        return level != null ? level : 0;
    }

    public void setEfficiencyLevel(int level) {
        this.pdc.set(KEY_EFFICIENCY, PersistentDataType.INTEGER, level);
        this.saveChanges();
    }

    public int getFortuneLevel() {
        Integer level = (Integer)this.pdc.get(KEY_FORTUNE, PersistentDataType.INTEGER);
        return level != null ? level : 0;
    }

    public void setFortuneLevel(int level) {
        this.pdc.set(KEY_FORTUNE, PersistentDataType.INTEGER, level);
        this.saveChanges();
    }

    public int getUnbreakingLevel() {
        Integer level = (Integer)this.pdc.get(KEY_UNBREAKING, PersistentDataType.INTEGER);
        return level != null ? level : 0;
    }

    public void setUnbreakingLevel(int level) {
        this.pdc.set(KEY_UNBREAKING, PersistentDataType.INTEGER, level);
        this.saveChanges();
    }

    public String getBoundTo() {
        return this.pdc.get(KEY_BOUND_TO, PersistentDataType.STRING);
    }

    public void setBoundTo(String uuid) {
        if (uuid == null) {
            this.pdc.remove(KEY_BOUND_TO);
        } else {
            this.pdc.set(KEY_BOUND_TO, PersistentDataType.STRING, uuid);
        }
        this.saveChanges();
    }

    public String getBoundName() {
        return this.pdc.get(KEY_BOUND_NAME, PersistentDataType.STRING);
    }

    public void setBoundName(String name) {
        if (name == null) {
            this.pdc.remove(KEY_BOUND_NAME);
        } else {
            this.pdc.set(KEY_BOUND_NAME, PersistentDataType.STRING, name);
        }
        this.saveChanges();
    }

    public boolean isBound() {
        return this.getBoundTo() != null;
    }

    public int getUpgradeLevel(UpgradeType type) {
        return switch (type) {
            default -> throw new IncompatibleClassChangeError();
            case UpgradeType.EFFICIENCY -> this.getEfficiencyLevel();
            case UpgradeType.FORTUNE -> this.getFortuneLevel();
            case UpgradeType.UNBREAKING -> this.getUnbreakingLevel();
        };
    }

    public void setUpgradeLevel(UpgradeType type, int level) {
        switch (type) {
            case EFFICIENCY: {
                this.setEfficiencyLevel(level);
                break;
            }
            case FORTUNE: {
                this.setFortuneLevel(level);
                break;
            }
            case UNBREAKING: {
                this.setUnbreakingLevel(level);
            }
        }
    }

    private void saveChanges() {
        this.item.setItemMeta(this.meta);
    }

    public boolean isUnbreakable() {
        Byte value = (Byte)this.pdc.get(KEY_IS_UNBREAKABLE, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    public void setUnbreakable(boolean unbreakable) {
        this.pdc.set(KEY_IS_UNBREAKABLE, PersistentDataType.BYTE, ((byte)(unbreakable ? 1 : 0)));
        this.saveChanges();
    }

    public boolean isSilkTouch() {
        Byte value = (Byte)this.pdc.get(KEY_IS_SILK_TOUCH, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    public void setSilkTouch(boolean silkTouch) {
        this.pdc.set(KEY_IS_SILK_TOUCH, PersistentDataType.BYTE, ((byte)(silkTouch ? 1 : 0)));
        this.saveChanges();
    }

    public boolean hasToggleStone() {
        Byte value = (Byte)this.pdc.get(KEY_HAS_TOGGLE_STONE, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    public void setToggleStone(boolean hasStone) {
        this.pdc.set(KEY_HAS_TOGGLE_STONE, PersistentDataType.BYTE, ((byte)(hasStone ? 1 : 0)));
        this.saveChanges();
    }

    public ItemStack getItem() {
        return this.item;
    }
}



