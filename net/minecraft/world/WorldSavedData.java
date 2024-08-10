package net.minecraft.world;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 用于保存和管理世界数据的抽象类。
 * 该类包含了与存储世界数据相关的基本方法和字段。
 */
public abstract class WorldSavedData
{
    /** 数据名称，用于标识数据。 */
    public final String mapName;
    
    /** 标记数据是否需要保存到磁盘。 */
    private boolean dirty;

    /** 类标识符 */
    private static final String __OBFID = "CL_00000580";

    /**
     * 构造函数，创建一个新的 WorldSavedData 实例。
     *
     * @param p_i2141_1_ 数据名称
     */
    public WorldSavedData(String p_i2141_1_)
    {
        this.mapName = p_i2141_1_;
    }

    /**
     * 从 NBTTagCompound 读取数据到该数据实例中。
     * 子类需要实现此方法以解析存储在 NBTTagCompound 中的数据。
     *
     * @param p_76184_1_ 用于读取数据的 NBTTagCompound
     */
    public abstract void readFromNBT(NBTTagCompound p_76184_1_);

    /**
     * 将数据从该数据实例写入 NBTTagCompound。
     * 子类需要实现此方法以将数据保存到 NBTTagCompound 中。
     *
     * @param p_76187_1_ 用于写入数据的 NBTTagCompound
     */
    public abstract void writeToNBT(NBTTagCompound p_76187_1_);

    /**
     * 标记该数据为“脏”的状态，即需要在下一次保存时写入磁盘。
     */
    public void markDirty()
    {
        this.setDirty(true);
    }

    /**
     * 设置该数据的“脏”状态，表示数据是否需要保存到磁盘。
     *
     * @param p_76186_1_ 如果为 true，则数据需要保存到磁盘；否则不需要
     */
    public void setDirty(boolean p_76186_1_)
    {
        this.dirty = p_76186_1_;
    }

    /**
     * 判断该数据是否需要保存到磁盘。
     *
     * @return 如果数据需要保存到磁盘返回 true，否则返回 false
     */
    public boolean isDirty()
    {
        return this.dirty;
    }
}
