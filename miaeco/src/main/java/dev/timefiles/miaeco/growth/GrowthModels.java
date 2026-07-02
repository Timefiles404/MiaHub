package dev.timefiles.miaeco.growth;

import dev.timefiles.miaeco.model.TreeForm;
import dev.timefiles.miaeco.model.TreeSpecies;

import java.util.EnumMap;
import java.util.Map;

/**
 * 树种结构类型 → 形态生成模型 的注册表。生长服务据此为每棵树选对模型。
 * 所有模型无状态、确定性、线程安全，可安全共享。
 */
public final class GrowthModels {

    private static final Map<TreeForm, GrowthModel> MODELS = new EnumMap<>(TreeForm.class);
    private static final GrowthModel DEFAULT = new BroadleafModel();

    static {
        MODELS.put(TreeForm.BROADLEAF, DEFAULT);
        MODELS.put(TreeForm.BIRCH, new BirchModel());
        MODELS.put(TreeForm.DARK_OAK, new DarkOakModel());
        MODELS.put(TreeForm.JUNGLE, new JungleModel());
        MODELS.put(TreeForm.CONIFER, new ConiferModel());
        MODELS.put(TreeForm.ACACIA, new AcaciaModel());
    }

    private GrowthModels() { }

    public static GrowthModel forSpecies(TreeSpecies sp) {
        return MODELS.getOrDefault(sp.form(), DEFAULT);
    }
}
