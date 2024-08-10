package net.minecraft.world;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.biome.WorldChunkManagerHell;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderFlat;
import net.minecraft.world.gen.ChunkProviderGenerate;
import net.minecraft.world.gen.FlatGeneratorInfo;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.common.DimensionManager;

/**
 * 世界提供者基类，定义了不同维度的特性和行为。
 */
public abstract class WorldProvider
{
    public static final float[] moonPhaseFactors = new float[] {1.0F, 0.75F, 0.5F, 0.25F, 0.0F, 0.25F, 0.5F, 0.75F};
    /** 当前世界对象 */
    public World worldObj;
    /** 世界的地形类型 */
    public WorldType terrainType;
    /** 世界生成器选项 */
    public String field_82913_c;
    /** 世界块管理器，用于生成区块 */
    public WorldChunkManager worldChunkMgr;
    /** 是否为地狱维度的世界提供者 */
    public boolean isHellWorld;
    /** 该世界是否没有天空，用于天气和光照计算 */
    public boolean hasNoSky;
    /** 光亮度转换表 */
    public float[] lightBrightnessTable = new float[16];
    /** 维度 ID（例如 -1: 地狱，0: 主世界，1: 末地） */
    public int dimensionId;
    /** 日出/日落颜色数组（RGBA） */
    private float[] colorsSunriseSunset = new float[4];
    private static final String __OBFID = "CL_00000386";

    /**
     * 关联现有世界到 WorldProvider，并设置其光亮度表。
     * @param p_76558_1_ 要关联的世界
     */
    public final void registerWorld(World p_76558_1_)
    {
        this.worldObj = p_76558_1_;
        this.terrainType = p_76558_1_.getWorldInfo().getTerrainType();
        this.field_82913_c = p_76558_1_.getWorldInfo().getGeneratorOptions();
        this.registerWorldChunkManager();
        this.generateLightBrightnessTable();
    }

    /**
     * 创建光亮度转换表
     */
    protected void generateLightBrightnessTable()
    {
        float f = 0.0F;

        for (int i = 0; i <= 15; ++i)
        {
            float f1 = 1.0F - (float)i / 15.0F;
            this.lightBrightnessTable[i] = (1.0F - f1) / (f1 * 3.0F + 1.0F) * (1.0F - f) + f;
        }
    }

    /**
     * 为 WorldProvider 创建一个新的世界块管理器
     */
    protected void registerWorldChunkManager()
    {
        this.worldChunkMgr = terrainType.getChunkManager(worldObj);
    }

    /**
     * 返回一个新的区块生成器，用于为此世界生成区块
     * @return 区块生成器
     */
    public IChunkProvider createChunkGenerator()
    {
        return terrainType.getChunkGenerator(worldObj, field_82913_c);
    }

    /**
     * 检查指定的 x, z 坐标是否可以作为地图生成点
     * @param p_76566_1_ x 坐标
     * @param p_76566_2_ z 坐标
     * @return 如果可以作为生成点则返回 true
     */
    public boolean canCoordinateBeSpawn(int p_76566_1_, int p_76566_2_)
    {
        return this.worldObj.getTopBlock(p_76566_1_, p_76566_2_) == Blocks.grass;
    }

    /**
     * 计算相对于指定时间（通常是世界时间）的太阳和月亮的角度
     * @param p_76563_1_ 世界时间
     * @param p_76563_3_ 偏移量
     * @return 计算出的角度
     */
    public float calculateCelestialAngle(long p_76563_1_, float p_76563_3_)
    {
        int j = (int)(p_76563_1_ % 24000L);
        float f1 = ((float)j + p_76563_3_) / 24000.0F - 0.25F;

        if (f1 < 0.0F)
        {
            ++f1;
        }

        if (f1 > 1.0F)
        {
            --f1;
        }

        float f2 = f1;
        f1 = 1.0F - (float)((Math.cos((double)f1 * Math.PI) + 1.0D) / 2.0D);
        f1 = f2 + (f1 - f2) / 3.0F;
        return f1;
    }
    /**
     * 获取月相
     * @param p_76559_1_ 世界时间
     * @return 月相
     */
    public int getMoonPhase(long p_76559_1_)
    {
        return (int)(p_76559_1_ / 24000L % 8L + 8L) % 8;
    }

    /**
     * 返回是否为“主表面世界”，如果在地狱或末地维度则返回 false
     * @return 是否为主表面世界
     */
    public boolean isSurfaceWorld()
    {
        return true;
    }

    /**
     * 返回日出/日落颜色数组
     * @param p_76560_1_ 时间
     * @param p_76560_2_ 偏移量
     * @return 日出/日落颜色数组
     */
    @SideOnly(Side.CLIENT)
    public float[] calcSunriseSunsetColors(float p_76560_1_, float p_76560_2_)
    {
        float f2 = 0.4F;
        float f3 = MathHelper.cos(p_76560_1_ * (float)Math.PI * 2.0F) - 0.0F;
        float f4 = -0.0F;

        if (f3 >= f4 - f2 && f3 <= f4 + f2)
        {
            float f5 = (f3 - f4) / f2 * 0.5F + 0.5F;
            float f6 = 1.0F - (1.0F - MathHelper.sin(f5 * (float)Math.PI)) * 0.99F;
            f6 *= f6;
            this.colorsSunriseSunset[0] = f5 * 0.3F + 0.7F;
            this.colorsSunriseSunset[1] = f5 * f5 * 0.7F + 0.2F;
            this.colorsSunriseSunset[2] = f5 * f5 * 0.0F + 0.2F;
            this.colorsSunriseSunset[3] = f6;
            return this.colorsSunriseSunset;
        }
        else
        {
            return null;
        }
    }

    /**
     * 返回生物群系特定的雾颜色
     * @param p_76562_1_ 时间
     * @param p_76562_2_ 偏移量
     * @return 雾颜色向量
     */
    @SideOnly(Side.CLIENT)
    public Vec3 getFogColor(float p_76562_1_, float p_76562_2_)
    {
        float f2 = MathHelper.cos(p_76562_1_ * (float)Math.PI * 2.0F) * 2.0F + 0.5F;

        if (f2 < 0.0F)
        {
            f2 = 0.0F;
        }

        if (f2 > 1.0F)
        {
            f2 = 1.0F;
        }

        float f3 = 0.7529412F;
        float f4 = 0.84705883F;
        float f5 = 1.0F;
        f3 *= f2 * 0.94F + 0.06F;
        f4 *= f2 * 0.94F + 0.06F;
        f5 *= f2 * 0.91F + 0.09F;
        return Vec3.createVectorHelper((double)f3, (double)f4, (double)f5);
    }

    /**
     * 如果玩家可以在此维度复活，则返回 true（例如主世界为 true，地狱为 false）
     * @return 玩家是否可以复活
     */
    public boolean canRespawnHere()
    {
        return true;
    }

    /**
     * 根据维度 ID 获取提供者
     * @param p_76570_0_ 维度 ID
     * @return 维度提供者
     */
    public static WorldProvider getProviderForDimension(int p_76570_0_)
    {
        return DimensionManager.createProviderFor(p_76570_0_);
    }

    /**
     * 获取云层的渲染高度
     * @return 云层高度
     */
    @SideOnly(Side.CLIENT)
    public float getCloudHeight()
    {
        return this.terrainType.getCloudHeight();
    }


    @SideOnly(Side.CLIENT)
    public boolean isSkyColored()
    {
        return true;
    }

    /**
     * 获取进入此维度时的硬编码传送门位置。
     * @return 传送门坐标，如果没有则返回 null
     */
    public ChunkCoordinates getEntrancePortalLocation()
    {
        return null;
    }

    /**
     * 返回世界的平均地面高度。
     * @return 平均地面高度
     */
    public int getAverageGroundLevel()
    {
        return this.terrainType.getMinimumSpawnHeight(this.worldObj);
    }


    /**
     * 返回该维度是否应显示虚空粒子，并根据用户的 Y 偏移拉取远处的平面。
     * @return 如果有虚空粒子则返回 true
     */
    @SideOnly(Side.CLIENT)
    public boolean getWorldHasVoidParticles()
    {
        return this.terrainType.hasVoidParticles(this.hasNoSky);
    }

    /**
     * 返回一个双精度值，表示相对于地图顶部的 Y 值，在该值下虚空雾霾达到最大。
     * 默认因子为 0.03125 相对于 256，因此虚空雾霾将在 (256*0.03125) 或 8 处达到最大。
     * @return 虚空雾霾的 Y 值因子
     */
    @SideOnly(Side.CLIENT)
    public double getVoidFogYFactor()
    {
        return this.terrainType.voidFadeMagnitude();
    }

    /**
     * 返回给定的 X,Z 坐标是否应显示环境雾。
     * @param p_76568_1_ X 坐标
     * @param p_76568_2_ Z 坐标
     * @return 如果应显示雾则返回 true
     */
    @SideOnly(Side.CLIENT)
    public boolean doesXZShowFog(int p_76568_1_, int p_76568_2_)
    {
        return false;
    }

    /**
     * 返回维度的名称，例如 "The End"、"Nether" 或 "Overworld"。
     * @return 维度名称
     */
    public abstract String getDimensionName();

    /*======================================= Forge Start =========================================*/
    private IRenderHandler skyRenderer = null;
    private IRenderHandler cloudRenderer = null;
    private IRenderHandler weatherRenderer = null;

    /**
     * 设置当前维度的维度 ID，用于默认的 getSaveFolder()。
     * 添加以允许默认提供程序为多个维度注册。
     * @param dim 维度 ID
     */
    public void setDimension(int dim)
    {
        this.dimensionId = dim;
    }

    /**
     * 返回此 WorldProvider 保存到的世界文件夹的子文件夹名称。
     * 示例: DIM1, DIM-1
     * @return 保存世界区块的子文件夹名称
     */
    public String getSaveFolder()
    {
        return (dimensionId == 0 ? null : "DIM" + dimensionId);
    }

    /**
     * 用户转移到此维度时显示的消息。
     * @return 显示的消息
     */
    public String getWelcomeMessage()
    {
        if (this instanceof WorldProviderEnd)
        {
            return "Entering the End";
        }
        else if (this instanceof WorldProviderHell)
        {
            return "Entering the Nether";
        }
        return null;
    }

    /**
     * 用户从此维度转移出去时显示的消息。
     * @return 显示的消息
     */
    public String getDepartMessage()
    {
        if (this instanceof WorldProviderEnd)
        {
            return "Leaving the End";
        }
        else if (this instanceof WorldProviderHell)
        {
            return "Leaving the Nether";
        }
        return null;
    }

    /**
     * 维度的移动因子，相对于正常的主世界。
     * 这是在玩家转移维度时应用于玩家位置的因子。
     * 示例: 地狱的移动因子为 8.0
     * @return 移动因子
     */
    public double getMovementFactor()
    {
        if (this instanceof WorldProviderHell)
        {
            return 8.0;
        }
        return 1.0;
    }

    @SideOnly(Side.CLIENT)
    public IRenderHandler getSkyRenderer()
    {
        return this.skyRenderer;
    }

    @SideOnly(Side.CLIENT)
    public void setSkyRenderer(IRenderHandler skyRenderer)
    {
        this.skyRenderer = skyRenderer;
    }

    @SideOnly(Side.CLIENT)
    public IRenderHandler getCloudRenderer()
    {
        return cloudRenderer;
    }

    @SideOnly(Side.CLIENT)
    public void setCloudRenderer(IRenderHandler renderer)
    {
        cloudRenderer = renderer;
    }

    @SideOnly(Side.CLIENT)
    public IRenderHandler getWeatherRenderer()
    {
        return weatherRenderer;
    }

    @SideOnly(Side.CLIENT)
    public void setWeatherRenderer(IRenderHandler renderer)
    {
        weatherRenderer = renderer;
    }

    /**
     * 获取随机生成的出生点。
     * 在冒险模式下，或当没有天空的情况下，出生点将不被随机化。
     * @return 随机化的出生点坐标
     */
    public ChunkCoordinates getRandomizedSpawnPoint()
    {
        ChunkCoordinates chunkcoordinates = new ChunkCoordinates(this.worldObj.getSpawnPoint());

        boolean isAdventure = worldObj.getWorldInfo().getGameType() == GameType.ADVENTURE;
        int spawnFuzz = terrainType.getSpawnFuzz();
        int spawnFuzzHalf = spawnFuzz / 2;

        if (!hasNoSky && !isAdventure && net.minecraftforge.common.ForgeModContainer.defaultHasSpawnFuzz)
        {
            chunkcoordinates.posX += this.worldObj.rand.nextInt(spawnFuzz) - spawnFuzzHalf;
            chunkcoordinates.posZ += this.worldObj.rand.nextInt(spawnFuzz) - spawnFuzzHalf;
            chunkcoordinates.posY = this.worldObj.getTopSolidOrLiquidBlock(chunkcoordinates.posX, chunkcoordinates.posZ);
        }

        return chunkcoordinates;
    }

    /**
     * 确定在渲染地图时，光标是否应旋转，类似于在地狱中玩家的光标。
     *
     * @param entity 持有地图的实体，可以是玩家名称或帧的 ENTITYID
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 如果光标应旋转，则返回 true
     */
    public boolean shouldMapSpin(String entity, double x, double y, double z)
    {
        return dimensionId < 0;
    }

    /**
     * 确定玩家复活时将会被重生在哪个维度，通常会将其带回主世界。
     *
     * @param player 正在复活的玩家
     * @return 玩家将被重生的维度 ID
     */
    public int getRespawnDimension(EntityPlayerMP player)
    {
        return 0;
    }

    /*======================================= Start Moved From World =========================================*/
    /**
     * 获取指定坐标的生物群系。
     *
     * @param x X 坐标
     * @param z Z 坐标
     * @return 对应坐标的生物群系
     */
    public BiomeGenBase getBiomeGenForCoords(int x, int z)
    {
        return worldObj.getBiomeGenForCoordsBody(x, z);
    }
    /**
     * 检查当前是否是白天。
     *
     * @return 如果是白天返回 true，否则返回 false
     */
    public boolean isDaytime()
    {
        return worldObj.skylightSubtracted < 4;
    }

    /**
     * 获取当前的太阳亮度因子。
     * 0.0f 表示没有光线，1.0f 表示最大日光。
     * 此值用于计算天空光照值。
     *
     * @param par1 渲染时间部分值
     * @return 当前的亮度因子
     */
    public float getSunBrightnessFactor(float par1)
    {
        return worldObj.getSunBrightnessFactor(par1);
    }

    /**
     * 计算当前的月亮相位因子。
     * 此因子对史莱姆的生成有效。
     * （此方法不影响月亮的渲染）
     *
     * @return 当前的月亮相位因子
     */
    public float getCurrentMoonPhaseFactor()
    {
        return worldObj.getCurrentMoonPhaseFactorBody();
    }

    /**
     * 获取天空的颜色。
     *
     * @param cameraEntity 观察实体
     * @param partialTicks 渲染时间部分值
     * @return 天空颜色的向量
     */
    @SideOnly(Side.CLIENT)
    public Vec3 getSkyColor(Entity cameraEntity, float partialTicks)
    {
        return worldObj.getSkyColorBody(cameraEntity, partialTicks);
    }

    /**
     * 绘制云层。
     *
     * @param partialTicks 渲染时间部分值
     * @return 云层的颜色向量
     */
    @SideOnly(Side.CLIENT)
    public Vec3 drawClouds(float partialTicks)
    {
        return worldObj.drawCloudsBody(partialTicks);
    }

    /**
     * 获取用于渲染天空的太阳亮度。
     *
     * @param par1 渲染时间部分值
     * @return 太阳亮度
     */
    @SideOnly(Side.CLIENT)
    public float getSunBrightness(float par1)
    {
        return worldObj.getSunBrightnessBody(par1);
    }

    /**
     * 获取用于渲染天空的星星亮度。
     *
     * @param par1 渲染时间部分值
     * @return 星星亮度
     */
    @SideOnly(Side.CLIENT)
    public float getStarBrightness(float par1)
    {
        return worldObj.getStarBrightnessBody(par1);
    }

    /**
     * 设置是否允许生成敌对和友好生物。
     *
     * @param allowHostile 是否允许生成敌对生物
     * @param allowPeaceful 是否允许生成友好生物
     */
    public void setAllowedSpawnTypes(boolean allowHostile, boolean allowPeaceful)
    {
        worldObj.spawnHostileMobs = allowHostile;
        worldObj.spawnPeacefulMobs = allowPeaceful;
    }

    /**
     * 计算初始天气。
     */
    public void calculateInitialWeather()
    {
        worldObj.calculateInitialWeatherBody();
    }

    /**
     * 更新天气状态。
     */
    public void updateWeather()
    {
        worldObj.updateWeatherBody();
    }

    /**
     * 判断指定位置的方块是否可以冻结。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param byWater 是否由水引起
     * @return 如果可以冻结返回 true，否则返回 false
     */
    public boolean canBlockFreeze(int x, int y, int z, boolean byWater)
    {
        return worldObj.canBlockFreezeBody(x, y, z, byWater);
    }

    /**
     * 判断指定位置是否可以下雪。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param checkLight 是否检查光照
     * @return 如果可以下雪返回 true，否则返回 false
     */
    public boolean canSnowAt(int x, int y, int z, boolean checkLight)
    {
        return worldObj.canSnowAtBody(x, y, z, checkLight);
    }

    /**
     * 设置世界时间。
     *
     * @param time 要设置的时间
     */
    public void setWorldTime(long time)
    {
        worldObj.worldInfo.setWorldTime(time);
    }

    /**
     * 获取世界的种子。
     *
     * @return 世界种子
     */
    public long getSeed()
    {
        return worldObj.worldInfo.getSeed();
    }

    /**
     * 获取世界时间。
     *
     * @return 世界时间
     */
    public long getWorldTime()
    {
        return worldObj.worldInfo.getWorldTime();
    }

    /**
     * 获取世界的出生点。
     *
     * @return 出生点坐标
     */
    public ChunkCoordinates getSpawnPoint()
    {
        WorldInfo info = worldObj.worldInfo;
        return new ChunkCoordinates(info.getSpawnX(), info.getSpawnY(), info.getSpawnZ());
    }

    /**
     * 设置世界的出生点。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     */
    public void setSpawnPoint(int x, int y, int z)
    {
        worldObj.worldInfo.setSpawnPosition(x, y, z);
    }

    /**
     * 判断玩家是否可以挖掘指定位置的方块。
     *
     * @param player 进行挖掘的玩家
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 如果可以挖掘返回 true，否则返回 false
     */
    public boolean canMineBlock(EntityPlayer player, int x, int y, int z)
    {
        return worldObj.canMineBlockBody(player, x, y, z);
    }

    /**
     * 判断指定位置的方块是否具有高湿度。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 如果具有高湿度返回 true，否则返回 false
     */
    public boolean isBlockHighHumidity(int x, int y, int z)
    {
        return worldObj.getBiomeGenForCoords(x, z).isHighHumidity();
    }

    /**
     * 获取维度的最大高度。
     *
     * @return 最大高度
     */
    public int getHeight()
    {
        return 256;
    }

    /**
     * 获取实际的高度。
     *
     * @return 实际高度
     */
    public int getActualHeight()
    {
        return hasNoSky ? 128 : 256;
    }

    /**
     * 获取地平线的高度。
     *
     * @return 地平线高度
     */
    public double getHorizon()
    {
        return worldObj.worldInfo.getTerrainType().getHorizon(worldObj);
    }

    /**
     * 重置雨天和雷暴状态。
     */
    public void resetRainAndThunder()
    {
        worldObj.worldInfo.setRainTime(0);
        worldObj.worldInfo.setRaining(false);
        worldObj.worldInfo.setThunderTime(0);
        worldObj.worldInfo.setThundering(false);
    }

    /**
     * 判断指定区块是否可以生成闪电。
     *
     * @param chunk 要检查的区块
     * @return 如果可以生成闪电返回 true，否则返回 false
     */
    public boolean canDoLightning(Chunk chunk)
    {
        return true;
    }

    /**
     * 判断指定区块是否可以生成雨、雪或冰。
     *
     * @param chunk 要检查的区块
     * @return 如果可以生成雨、雪或冰返回 true，否则返回 false
     */
    public boolean canDoRainSnowIce(Chunk chunk)
    {
        return true;
    }
}
