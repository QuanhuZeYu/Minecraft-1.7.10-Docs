package net.minecraft.world;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.material.Material;
import net.minecraft.command.IEntitySelector;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.profiler.Profiler;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.Direction;
import net.minecraft.util.Facing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Vec3;
import net.minecraft.village.VillageCollection;
import net.minecraft.village.VillageSiege;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldInfo;

import cpw.mods.fml.common.FMLLog;

import com.google.common.collect.ImmutableSetMultimap;

import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.common.WorldSpecificSaveHandler;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.entity.PlaySoundAtEntityEvent;
import net.minecraft.entity.EnumCreatureType;

public abstract class World implements IBlockAccess
{
    /**
     * 用于在 getEntitiesWithinAABB 函数中扩展搜索区域的半径。
     * 如果某个实体的半径大于此值，模组开发者应该将此变量设置为更高的值。
     */
    public static double MAX_ENTITY_RADIUS = 2.0D;
    /** 存储每个世界的数据 */
    public final MapStorage perWorldStorage;

    /** 布尔值；如果为 true，则由 scheduleBlockUpdate 安排的更新会立即发生 */
    public boolean scheduledUpdatesAreImmediate;
    /** 所有当前加载的区块中的所有实体列表 */
    public List<net.minecraft.entity.Entity> loadedEntityList = new ArrayList();
    /** 已卸载实体的列表 */
    protected List<net.minecraft.entity.Entity> unloadedEntityList = new ArrayList();
    /** 世界中加载的所有 TileEntity 的列表 */
    public List<net.minecraft.tileentity.TileEntity> loadedTileEntityList = new ArrayList();
    /** 新添加的 TileEntity 列表 */
    private List addedTileEntityList = new ArrayList();
    /** 一个列表，可能用于存储其他数据 */
    private List field_147483_b = new ArrayList();
    /** 世界中的玩家实体列表 */
    public List<net.minecraft.entity.player.EntityPlayer> playerEntities = new ArrayList();
    /** 所有闪电实体的列表 */
    public List<net.minecraft.entity.Entity> weatherEffects = new ArrayList();
    /** 云彩的颜色，使用 RGB 颜色值表示 */
    private long cloudColour = 16777215L;
    /** 从完全的日光中减去的光量 */
    public int skylightSubtracted;
    /**
     * 包含用于块更新的当前线性同余生成器种子。
     * 使用 A 值为 3 和 C 值为 0x3c6ef35f 的线性同余生成器，生成不适合在 16x128x16 区域中选择随机块的序列。
     */
    protected int updateLCG = (new Random()).nextInt();
    /** 用于在区块内快速生成随机数的魔法数字 */
    protected final int DIST_HASH_MAGIC = 1013904223;
    /** 上一轮的降雨强度 */
    public float prevRainingStrength;
    /** 当前的降雨强度 */
    public float rainingStrength;
    /** 上一轮的雷电强度 */
    public float prevThunderingStrength;
    /** 当前的雷电强度 */
    public float thunderingStrength;
    /**
     * 每当在单人游戏中生成闪电时设置为 2。若值大于 0，在 updateWeather() 中递减。此值似乎未被使用。
     */
    public int lastLightningBolt;
    /** 游戏的难度设置 (0 - 3) */
    public EnumDifficulty difficultySetting;
    /** 世界的随机数生成器 */
    public Random rand = new Random();
    /** World 使用的 WorldProvider 实例 */
    public final WorldProvider provider;

    protected List<net.minecraft.world.IWorldAccess> worldAccesses = new ArrayList();
    /** 处理区块操作和缓存 */
    protected IChunkProvider chunkProvider;
    /** 保存处理程序 */
    protected final ISaveHandler saveHandler;
    /** 保存关于世界的信息（磁盘上的大小、时间、生成点、种子等） */
    protected WorldInfo worldInfo;
    /** 在尝试找到生成点时设置为 true */
    public boolean findingSpawnPoint;
    /** 存储世界中的数据 */
    public MapStorage mapStorage;
    /** 村庄集合对象 */
    public VillageCollection villageCollectionObj;
    /** 村庄围攻对象 */
    protected final VillageSiege villageSiegeObj = new VillageSiege(this);
    /** 世界分析器 */
    public final Profiler theProfiler;
    /** 用于处理时间和日期的日历实例 */
    private final Calendar theCalendar = Calendar.getInstance();
    /** 世界记分板 */
    protected Scoreboard worldScoreboard = new Scoreboard();
    /** 如果是客户端世界则为 true，否则为服务器世界则为 false */
    public boolean isRemote;
    /** 更新位置的坐标对集合 */
    protected Set<net.minecraft.world.ChunkCoordIntPair> activeChunkSet = new HashSet();
    /** 直到下一个随机环境音播放的刻度数 */
    private int ambientTickCountdown;
    /** 指示是否生成敌对生物 */
    protected boolean spawnHostileMobs;
    /** 指示是否生成和平生物的标志 */
    protected boolean spawnPeacefulMobs;
    /** 存储碰撞的包围盒列表 */
    private ArrayList collidingBoundingBoxes;
    /** 一个布尔值，可能用于某种标记 */
    private boolean field_147481_N;
    /**
     * 用于更新光照等级时的临时块和光照值列表。最多可容纳 32x32x32 块（光源的最大影响范围）。
     * 每个元素是一个打包的位值：0000000000LLLLzzzzzzyyyyyyxxxxxx。
     * 4 位 L 是在暗化块时使用的光照等级。6 位的 x、y 和 z 代表块相对于原始块的偏移量，加上 32（即，值为 31 代表 -1 的偏移量）。
     */
    int[] lightUpdateBlockList;
    private static final String __OBFID = "CL_00000140";
    /** 是否恢复块快照的标志 */
    public boolean restoringBlockSnapshots = false;
    /** 是否捕捉块快照的标志 */
    public boolean captureBlockSnapshots = false;
    /** 捕捉的块快照列表 */
    public ArrayList<net.minecraftforge.common.util.BlockSnapshot> capturedBlockSnapshots = new ArrayList<net.minecraftforge.common.util.BlockSnapshot>();

    /**
     * 获取给定 x/z 坐标的生物群系
     */
    public BiomeGenBase getBiomeGenForCoords(final int x, final int z)
    {
        return provider.getBiomeGenForCoords(x, z);
    }

    /**
     * 获取给定 x/z 坐标的生物群系（内部实现）
     *
     * @param x 坐标 x
     * @param z 坐标 z
     * @return 指定坐标的生物群系
     */
    public BiomeGenBase getBiomeGenForCoordsBody(final int x, final int z)
    {
        if (this.blockExists(x, 0, z))
        {
            Chunk chunk = this.getChunkFromBlockCoords(x, z);

            try
            {
                return chunk.getBiomeGenForWorldCoords(x & 15, z & 15, this.provider.worldChunkMgr);
            }
            catch (Throwable throwable)
            {
                CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Getting biome");
                CrashReportCategory crashreportcategory = crashreport.makeCategory("Coordinates of biome request");
                crashreportcategory.addCrashSectionCallable("Location", new Callable()
                {
                    private static final String __OBFID = "CL_00000141";
                    public String call()
                    {
                        return CrashReportCategory.getLocationInfo(x, 0, z);
                    }
                });
                throw new ReportedException(crashreport);
            }
        }
        else
        {
            return this.provider.worldChunkMgr.getBiomeGenAt(x, z);
        }
    }

    /**
     * 获取世界区块管理器
     *
     * @return 世界区块管理器
     */
    public WorldChunkManager getWorldChunkManager()
    {
        return this.provider.worldChunkMgr;
    }

    /**
     * 客户端专用构造函数，初始化世界对象。
     *
     * @param p_i45368_1_ 关卡存储处理器
     * @param p_i45368_2_ 世界名称
     * @param p_i45368_3_ 世界提供者
     * @param p_i45368_4_ 世界设置
     * @param p_i45368_5_ 性能分析器
     */
    @SideOnly(Side.CLIENT)
    public World(ISaveHandler p_i45368_1_, String p_i45368_2_, WorldProvider p_i45368_3_, WorldSettings p_i45368_4_, Profiler p_i45368_5_)
    {
        this.ambientTickCountdown = this.rand.nextInt(12000);
        this.spawnHostileMobs = true;
        this.spawnPeacefulMobs = true;
        this.collidingBoundingBoxes = new ArrayList();
        this.lightUpdateBlockList = new int[32768];
        this.saveHandler = p_i45368_1_;
        this.theProfiler = p_i45368_5_;
        this.worldInfo = new WorldInfo(p_i45368_4_, p_i45368_2_);
        this.provider = p_i45368_3_;
        perWorldStorage = new MapStorage((ISaveHandler)null);
    }

    /**
     * 完成世界设置的初始化。
     * 该方法确保 WorldClient 在维度初始化之前设置 mapstorage 对象。
     */
    // 进行分解，以便 WorldClient 有机会在维度初始化之前设置地图存储对象
    @SideOnly(Side.CLIENT)
    protected void finishSetup()
    {
        VillageCollection villagecollection = (VillageCollection)this.mapStorage.loadData(VillageCollection.class, "villages");

        if (villagecollection == null)
        {
            this.villageCollectionObj = new VillageCollection(this);
            this.mapStorage.setData("villages", this.villageCollectionObj);
        }
        else
        {
            this.villageCollectionObj = villagecollection;
            this.villageCollectionObj.func_82566_a(this);
        }

        // 确保维度 ID 没有被提供者重置
        int providerDim = this.provider.dimensionId;
        this.provider.registerWorld(this);
        this.provider.dimensionId = providerDim;
        this.chunkProvider = this.createChunkProvider();
        this.calculateInitialSkylight();
        this.calculateInitialWeather();
    }

    /**
     * 服务端构造函数，初始化世界对象。
     *
     * @param p_i45369_1_ 关卡存储处理器
     * @param p_i45369_2_ 世界名称
     * @param p_i45369_3_ 世界设置
     * @param p_i45369_4_ 世界提供者
     * @param p_i45369_5_ 性能分析器
     */
    public World(ISaveHandler p_i45369_1_, String p_i45369_2_, WorldSettings p_i45369_3_, WorldProvider p_i45369_4_, Profiler p_i45369_5_)
    {
        this.ambientTickCountdown = this.rand.nextInt(12000);
        this.spawnHostileMobs = true;
        this.spawnPeacefulMobs = true;
        this.collidingBoundingBoxes = new ArrayList();
        this.lightUpdateBlockList = new int[32768];
        this.saveHandler = p_i45369_1_;
        this.theProfiler = p_i45369_5_;
        this.mapStorage = getMapStorage(p_i45369_1_);
        this.worldInfo = p_i45369_1_.loadWorldInfo();

        if (p_i45369_4_ != null)
        {
            this.provider = p_i45369_4_;
        }
        else if (this.worldInfo != null && this.worldInfo.getVanillaDimension() != 0)
        {
            this.provider = WorldProvider.getProviderForDimension(this.worldInfo.getVanillaDimension());
        }
        else
        {
            this.provider = WorldProvider.getProviderForDimension(0);
        }

        if (this.worldInfo == null)
        {
            this.worldInfo = new WorldInfo(p_i45369_3_, p_i45369_2_);
        }
        else
        {
            this.worldInfo.setWorldName(p_i45369_2_);
        }

        this.provider.registerWorld(this);
        this.chunkProvider = this.createChunkProvider();

        if (this instanceof WorldServer)
        {
            this.perWorldStorage = new MapStorage(new WorldSpecificSaveHandler((WorldServer)this, p_i45369_1_));
        }
        else
        {
            this.perWorldStorage = new MapStorage((ISaveHandler)null);
        }

        if (!this.worldInfo.isInitialized())
        {
            try
            {
                this.initialize(p_i45369_3_);
            }
            catch (Throwable throwable1)
            {
                CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception initializing level");

                try
                {
                    this.addWorldInfoToCrashReport(crashreport);
                }
                catch (Throwable throwable)
                {
                    ;
                }

                throw new ReportedException(crashreport);
            }

            this.worldInfo.setServerInitialized(true);
        }

        VillageCollection villagecollection = (VillageCollection)this.perWorldStorage.loadData(VillageCollection.class, "villages");

        if (villagecollection == null)
        {
            this.villageCollectionObj = new VillageCollection(this);
            this.perWorldStorage.setData("villages", this.villageCollectionObj);
        }
        else
        {
            this.villageCollectionObj = villagecollection;
            this.villageCollectionObj.func_82566_a(this);
        }

        this.calculateInitialSkylight();
        this.calculateInitialWeather();
    }
    /** 静态字段，用于存储世界数据 */
    private static MapStorage s_mapStorage; // 静态字段，用于存储世界数据
    /** 静态字段，保存处理器 */
    private static ISaveHandler s_savehandler; // 静态字段，保存处理器
    //Provides a solution for different worlds getting different copies of the same data, potentially rewriting the data or causing race conditions/stale data
    //Buildcraft has suffered from the issue this fixes.  If you load the same data from two different worlds they can get two different copies of the same object, thus the last saved gets final say.
    /**
     * 获取世界数据存储实例。如果保存处理器发生变化或存储实例为 null，则重新创建存储实例。
     * 解决了不同世界之间可能存在的数据副本不同步的问题，防止数据被覆盖或导致竞争条件/过时的数据问题。
     * Buildcraft 曾遇到过这个问题，如果从两个不同的世界加载相同的数据，它们可能会得到两个不同的对象副本，
     * 这样最后保存的数据将决定最终结果。
     *
     * @param savehandler 当前的保存处理器
     * @return 当前的 MapStorage 实例
     */
    private MapStorage getMapStorage(ISaveHandler savehandler)
    {
        if (s_savehandler != savehandler || s_mapStorage == null)
        {
            s_mapStorage = new MapStorage(savehandler);
            s_savehandler = savehandler;
        }
        return s_mapStorage;
    }

    /**
     * 创建该世界的区块提供者。此方法在构造函数中调用。通过世界提供者获取提供者。
     *
     * @return 新创建的区块提供者
     */
    protected abstract IChunkProvider createChunkProvider();
    /**
     * 初始化世界设置，将服务器初始化标志设置为 true。
     *
     * @param p_72963_1_ 世界设置
     */
    protected void initialize(WorldSettings p_72963_1_)
    {
        this.worldInfo.setServerInitialized(true);
    }

    /**
     * 设置新的出生位置，通过在区块中随机 (x,z) 位置找到一个未覆盖的方块来设置。
     */
    @SideOnly(Side.CLIENT)
    public void setSpawnLocation()
    {
        this.setSpawnLocation(8, 64, 8);
    }
    /**
     * 获取给定 (x, z) 坐标的顶部方块。
     * 从 y = 63 开始向下查找，直到找到空气方块，返回该位置的方块。
     *
     * @param x 方块的 x 坐标
     * @param z 方块的 z 坐标
     * @return 顶部方块
     */
    public Block getTopBlock(int x, int z)
    {
        int k;

        for (k = 63; !this.isAirBlock(x, k + 1, z); ++k)
        {
            ;
        }

        return this.getBlock(x, k, z);
    }
    /**
     * 获取指定坐标处的方块。如果坐标超出范围或找不到对应的区块，则返回空气方块。
     *
     * @param p_147439_1_ 方块的 x 坐标
     * @param p_147439_2_ 方块的 y 坐标
     * @param p_147439_3_ 方块的 z 坐标
     * @return 指定坐标处的方块
     */
    public Block getBlock(int p_147439_1_, int p_147439_2_, int p_147439_3_)
    {
        if (p_147439_1_ >= -30000000 && p_147439_3_ >= -30000000 && p_147439_1_ < 30000000 && p_147439_3_ < 30000000 && p_147439_2_ >= 0 && p_147439_2_ < 256)
        {
            Chunk chunk = null;

            try
            {
                chunk = this.getChunkFromChunkCoords(p_147439_1_ >> 4, p_147439_3_ >> 4);
                return chunk.getBlock(p_147439_1_ & 15, p_147439_2_, p_147439_3_ & 15);
            }
            catch (Throwable throwable)
            {
                CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Exception getting block type in world");
                CrashReportCategory crashreportcategory = crashreport.makeCategory("Requested block coordinates");
                crashreportcategory.addCrashSection("Found chunk", Boolean.valueOf(chunk == null));
                crashreportcategory.addCrashSection("Location", CrashReportCategory.getLocationInfo(p_147439_1_, p_147439_2_, p_147439_3_));
                throw new ReportedException(crashreport);
            }
        }
        else
        {
            return Blocks.air;
        }
    }
    /**
     * 判断指定坐标的方块是否为空气方块。
     *
     * @param x 方块的 x 坐标
     * @param y 方块的 y 坐标
     * @param z 方块的 z 坐标
     * @return 如果指定坐标处的方块为空气方块，则返回 true，否则返回 false
     */
    public boolean isAirBlock(int x, int y, int z)
    {
        Block block = this.getBlock(x, y, z);
        return block.isAir(this, x, y, z);
    }

    /**
     * 判断在指定的世界坐标 (x, y, z) 处是否存在方块。
     *
     * @param p_72899_1_ 方块的 x 坐标
     * @param p_72899_2_ 方块的 y 坐标
     * @param p_72899_3_ 方块的 z 坐标
     * @return 如果指定坐标处存在方块，则返回 true，否则返回 false
     */
    public boolean blockExists(int p_72899_1_, int p_72899_2_, int p_72899_3_)
    {
        return p_72899_2_ >= 0 && p_72899_2_ < 256 ? this.chunkExists(p_72899_1_ >> 4, p_72899_3_ >> 4) : false;
    }

    /**
     * 检查给定方块的距离 (p_72873_4_) 内的所有区块是否存在。
     *
     * @param p_72873_1_ 方块的 x 坐标
     * @param p_72873_2_ 方块的 y 坐标
     * @param p_72873_3_ 方块的 z 坐标
     * @param p_72873_4_ 检查的范围
     * @return 如果指定距离内的所有区块存在，则返回 true，否则返回 false
     */
    public boolean doChunksNearChunkExist(int p_72873_1_, int p_72873_2_, int p_72873_3_, int p_72873_4_)
    {
        return this.checkChunksExist(p_72873_1_ - p_72873_4_, p_72873_2_ - p_72873_4_, p_72873_3_ - p_72873_4_, p_72873_1_ + p_72873_4_, p_72873_2_ + p_72873_4_, p_72873_3_ + p_72873_4_);
    }

    /**
     * 检查给定的最小和最大坐标之间的所有区块是否存在。
     *
     * @param p_72904_1_ 最小 x 坐标
     * @param p_72904_2_ 最小 y 坐标
     * @param p_72904_3_ 最小 z 坐标
     * @param p_72904_4_ 最大 x 坐标
     * @param p_72904_5_ 最大 y 坐标
     * @param p_72904_6_ 最大 z 坐标
     * @return 如果指定范围内的所有区块存在，则返回 true，否则返回 false
     */
    public boolean checkChunksExist(int p_72904_1_, int p_72904_2_, int p_72904_3_, int p_72904_4_, int p_72904_5_, int p_72904_6_)
    {
        if (p_72904_5_ >= 0 && p_72904_2_ < 256)
        {
            p_72904_1_ >>= 4;
            p_72904_3_ >>= 4;
            p_72904_4_ >>= 4;
            p_72904_6_ >>= 4;

            for (int k1 = p_72904_1_; k1 <= p_72904_4_; ++k1)
            {
                for (int l1 = p_72904_3_; l1 <= p_72904_6_; ++l1)
                {
                    if (!this.chunkExists(k1, l1))
                    {
                        return false;
                    }
                }
            }

            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * 判断在给定的区块坐标 (x, y) 处是否存在区块。
     *
     * @param p_72916_1_ 区块的 x 坐标
     * @param p_72916_2_ 区块的 y 坐标
     * @return 如果指定坐标处的区块存在，则返回 true，否则返回 false
     */
    protected boolean chunkExists(int p_72916_1_, int p_72916_2_)
    {
        return this.chunkProvider.chunkExists(p_72916_1_, p_72916_2_);
    }

    /**
     * 根据给定的方块坐标 (x, z) 返回对应的区块。
     *
     * @param p_72938_1_ 方块的 x 坐标
     * @param p_72938_2_ 方块的 z 坐标
     * @return 包含指定坐标的区块
     */
    public Chunk getChunkFromBlockCoords(int p_72938_1_, int p_72938_2_)
    {
        return this.getChunkFromChunkCoords(p_72938_1_ >> 4, p_72938_2_ >> 4);
    }

    /**
     * 根据给定的区块坐标 (x, y) 返回对应的区块。
     *
     * @param p_72964_1_ 区块的 x 坐标
     * @param p_72964_2_ 区块的 z 坐标
     * @return 指定坐标的区块
     */
    public Chunk getChunkFromChunkCoords(int p_72964_1_, int p_72964_2_)
    {
        return this.chunkProvider.provideChunk(p_72964_1_, p_72964_2_);
    }

    /**
     * 设置指定位置的方块 ID 和元数据。
     * 标志 1 会导致方块更新。标志 2 会将更改发送给客户端（通常需要）。标志 4 防止方块重新渲染（如果这是客户端世界）。标志可以相加。
     *
     * @param x 方块的 x 坐标
     * @param y 方块的 y 坐标
     * @param z 方块的 z 坐标
     * @param blockIn 新的方块
     * @param metadataIn 新的元数据
     * @param flags 标志，用于控制方块更新和客户端通知等
     * @return 如果方块成功设置，则返回 true，否则返回 false
     */
    public boolean setBlock(int x, int y, int z, Block blockIn, int metadataIn, int flags)
    {
        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000)
        {
            if (y < 0)
            {
                return false;
            }
            else if (y >= 256)
            {
                return false;
            }
            else
            {
                Chunk chunk = this.getChunkFromChunkCoords(x >> 4, z >> 4);
                Block block1 = null;
                net.minecraftforge.common.util.BlockSnapshot blockSnapshot = null;

                if ((flags & 1) != 0)
                {
                    block1 = chunk.getBlock(x & 15, y, z & 15);
                }

                if (this.captureBlockSnapshots && !this.isRemote)
                {
                    blockSnapshot = net.minecraftforge.common.util.BlockSnapshot.getBlockSnapshot(this, x, y, z, flags);
                    this.capturedBlockSnapshots.add(blockSnapshot);
                }

                boolean flag = chunk.func_150807_a(x & 15, y, z & 15, blockIn, metadataIn);

                if (!flag && blockSnapshot != null)
                {
                    this.capturedBlockSnapshots.remove(blockSnapshot);
                    blockSnapshot = null;
                }

                this.theProfiler.startSection("checkLight");
                this.func_147451_t(x, y, z);
                this.theProfiler.endSection();

                if (flag && blockSnapshot == null) // Don't notify clients or update physics while capturing blockstates
                {
                    // Modularize client and physic updates
                    this.markAndNotifyBlock(x, y, z, chunk, block1, blockIn, flags);
                }

                return flag;
            }
        }
        else
        {
            return false;
        }
    }

    // 从原始 setBlock(int x, int y, int z, Block blockIn, intmetadataIn, int flags) 方法中分离出来，以便直接发送客户端和物理更新
    /**
     * 标记并通知方块的变化。直接发送客户端和物理更新。
     *
     * @param x 方块的 x 坐标
     * @param y 方块的 y 坐标
     * @param z 方块的 z 坐标
     * @param chunk 包含方块的区块
     * @param oldBlock 原始方块
     * @param newBlock 新方块
     * @param flag 标志，用于控制方块更新和客户端通知等
     */
    public void markAndNotifyBlock(int x, int y, int z, Chunk chunk, Block oldBlock, Block newBlock, int flag)
    {
        if ((flag & 2) != 0 && (chunk == null || chunk.func_150802_k()))
        {
            this.markBlockForUpdate(x, y, z);
        }

        if (!this.isRemote && (flag & 1) != 0)
        {
            this.notifyBlockChange(x, y, z, oldBlock);

            if (newBlock.hasComparatorInputOverride())
            {
                this.func_147453_f(x, y, z, newBlock);
            }
        }
    }

    /**
     * 返回指定坐标 (x, y, z) 处的方块元数据。
     *
     * @param p_72805_1_ 方块的 x 坐标
     * @param p_72805_2_ 方块的 y 坐标
     * @param p_72805_3_ 方块的 z 坐标
     * @return 指定坐标处的方块元数据，如果坐标无效则返回 0
     */
    public int getBlockMetadata(int p_72805_1_, int p_72805_2_, int p_72805_3_)
    {
        if (p_72805_1_ >= -30000000 && p_72805_3_ >= -30000000 && p_72805_1_ < 30000000 && p_72805_3_ < 30000000)
        {
            if (p_72805_2_ < 0)
            {
                return 0;
            }
            else if (p_72805_2_ >= 256)
            {
                return 0;
            }
            else
            {
                Chunk chunk = this.getChunkFromChunkCoords(p_72805_1_ >> 4, p_72805_3_ >> 4);
                p_72805_1_ &= 15;
                p_72805_3_ &= 15;
                return chunk.getBlockMetadata(p_72805_1_, p_72805_2_, p_72805_3_);
            }
        }
        else
        {
            return 0;
        }
    }

    /**
     * 设置方块的元数据，并根据标志通知方块发生变化。
     * 标志 1 会将更改通知给客户端，标志 2 会在方块变化时进行更新（当方块变化时，通常需要标志 2），标志 4 防止方块重新渲染（仅客户端世界）。
     *
     * @param p_72921_1_ 方块的 x 坐标
     * @param p_72921_2_ 方块的 y 坐标
     * @param p_72921_3_ 方块的 z 坐标
     * @param p_72921_4_ 新的元数据
     * @param p_72921_5_ 标志，用于控制方块更新和客户端通知等
     * @return 如果元数据成功设置，则返回 true，否则返回 false
     */
    public boolean setBlockMetadataWithNotify(int p_72921_1_, int p_72921_2_, int p_72921_3_, int p_72921_4_, int p_72921_5_)
    {
        if (p_72921_1_ >= -30000000 && p_72921_3_ >= -30000000 && p_72921_1_ < 30000000 && p_72921_3_ < 30000000)
        {
            if (p_72921_2_ < 0)
            {
                return false;
            }
            else if (p_72921_2_ >= 256)
            {
                return false;
            }
            else
            {
                Chunk chunk = this.getChunkFromChunkCoords(p_72921_1_ >> 4, p_72921_3_ >> 4);
                int j1 = p_72921_1_ & 15;
                int k1 = p_72921_3_ & 15;
                boolean flag = chunk.setBlockMetadata(j1, p_72921_2_, k1, p_72921_4_);

                if (flag)
                {
                    Block block = chunk.getBlock(j1, p_72921_2_, k1);

                    if ((p_72921_5_ & 2) != 0 && (!this.isRemote || (p_72921_5_ & 4) == 0) && chunk.func_150802_k())
                    {
                        this.markBlockForUpdate(p_72921_1_, p_72921_2_, p_72921_3_);
                    }

                    if (!this.isRemote && (p_72921_5_ & 1) != 0)
                    {
                        this.notifyBlockChange(p_72921_1_, p_72921_2_, p_72921_3_, block);

                        if (block.hasComparatorInputOverride())
                        {
                            this.func_147453_f(p_72921_1_, p_72921_2_, p_72921_3_, block);
                        }
                    }
                }

                return flag;
            }
        }
        else
        {
            return false;
        }
    }

    /**
     * 将指定位置的方块设置为空气，并通知相关系统方块发生变化。
     *
     * @param x 方块的 x 坐标
     * @param y 方块的 y 坐标
     * @param z 方块的 z 坐标
     * @return 如果方块成功设置为空气，则返回 true，否则返回 false
     */
    public boolean setBlockToAir(int x, int y, int z)
    {
        return this.setBlock(x, y, z, Blocks.air, 0, 3);
    }
    /**
     * 处理方块的删除操作，包括播放方块破坏效果，并根据需要掉落方块物品。
     *
     * @param x 方块的 x 坐标
     * @param y 方块的 y 坐标
     * @param z 方块的 z 坐标
     * @param dropBlock 是否掉落方块物品
     * @return 如果方块成功处理，则返回 true，否则返回 false
     */
    public boolean func_147480_a(int x, int y, int z, boolean dropBlock)
    {
        Block block = this.getBlock(x, y, z);

        if (block.getMaterial() == Material.air)
        {
            return false;
        }
        else
        {
            int l = this.getBlockMetadata(x, y, z);
            this.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (l << 12));

            if (dropBlock)
            {
                block.dropBlockAsItem(this, x, y, z, l, 0);
            }

            return this.setBlock(x, y, z, Blocks.air, 0, 3);
        }
    }

    /**
     * 根据给定坐标设置方块，并使用默认的标志（3）进行更新和通知。
     *
     * @param x 方块的 x 坐标
     * @param y 方块的 y 坐标
     * @param z 方块的 z 坐标
     * @param blockType 新的方块类型
     * @return 如果方块成功设置，则返回 true，否则返回 false
     */
    public boolean setBlock(int x, int y, int z, Block blockType)
    {
        return this.setBlock(x, y, z, blockType, 0, 3);
    }
    /**
     * 标记指定位置的方块以便更新。
     *
     * @param p_147471_1_ 方块的 x 坐标
     * @param p_147471_2_ 方块的 y 坐标
     * @param p_147471_3_ 方块的 z 坐标
     */
    public void markBlockForUpdate(int p_147471_1_, int p_147471_2_, int p_147471_3_)
    {
        for (int l = 0; l < this.worldAccesses.size(); ++l)
        {
            ((IWorldAccess)this.worldAccesses.get(l)).markBlockForUpdate(p_147471_1_, p_147471_2_, p_147471_3_);
        }
    }

    /**
     * 通知其他系统方块变化。
     *
     * @param p_147444_1_ 方块的 x 坐标
     * @param p_147444_2_ 方块的 y 坐标
     * @param p_147444_3_ 方块的 z 坐标
     * @param p_147444_4_ 变化的方块类型
     */
    public void notifyBlockChange(int p_147444_1_, int p_147444_2_, int p_147444_3_, Block p_147444_4_)
    {
        this.notifyBlocksOfNeighborChange(p_147444_1_, p_147444_2_, p_147444_3_, p_147444_4_);
    }

    /**
     * 标记一条垂直的方块线为“脏”状态，以便后续更新。
     * 这将更新光照并标记渲染范围以更新。
     *
     * @param p_72975_1_ 方块的 x 坐标
     * @param p_72975_2_ 方块的 y 坐标
     * @param p_72975_3_ 起始的 z 坐标
     * @param p_72975_4_ 结束的 z 坐标
     */
    public void markBlocksDirtyVertical(int p_72975_1_, int p_72975_2_, int p_72975_3_, int p_72975_4_)
    {
        int i1;

        if (p_72975_3_ > p_72975_4_)
        {
            i1 = p_72975_4_;
            p_72975_4_ = p_72975_3_;
            p_72975_3_ = i1;
        }

        if (!this.provider.hasNoSky)
        {
            for (i1 = p_72975_3_; i1 <= p_72975_4_; ++i1)
            {
                this.updateLightByType(EnumSkyBlock.Sky, p_72975_1_, i1, p_72975_2_);
            }
        }

        this.markBlockRangeForRenderUpdate(p_72975_1_, p_72975_3_, p_72975_2_, p_72975_1_, p_72975_4_, p_72975_2_);
    }
    /**
     * 标记一个方块范围的渲染更新区域。
     *
     * @param p_147458_1_ 起始 x 坐标
     * @param p_147458_2_ 起始 y 坐标
     * @param p_147458_3_ 起始 z 坐标
     * @param p_147458_4_ 结束 x 坐标
     * @param p_147458_5_ 结束 y 坐标
     * @param p_147458_6_ 结束 z 坐标
     */
    public void markBlockRangeForRenderUpdate(int p_147458_1_, int p_147458_2_, int p_147458_3_, int p_147458_4_, int p_147458_5_, int p_147458_6_)
    {
        for (int k1 = 0; k1 < this.worldAccesses.size(); ++k1)
        {
            ((IWorldAccess)this.worldAccesses.get(k1)).markBlockRangeForRenderUpdate(p_147458_1_, p_147458_2_, p_147458_3_, p_147458_4_, p_147458_5_, p_147458_6_);
        }
    }
    /**
     * 通知相邻的方块发生变化。
     *
     * @param p_147459_1_ 方块的 x 坐标
     * @param p_147459_2_ 方块的 y 坐标
     * @param p_147459_3_ 方块的 z 坐标
     * @param p_147459_4_ 变化的方块类型
     */
    public void notifyBlocksOfNeighborChange(int p_147459_1_, int p_147459_2_, int p_147459_3_, Block p_147459_4_)
    {
        this.notifyBlockOfNeighborChange(p_147459_1_ - 1, p_147459_2_, p_147459_3_, p_147459_4_);
        this.notifyBlockOfNeighborChange(p_147459_1_ + 1, p_147459_2_, p_147459_3_, p_147459_4_);
        this.notifyBlockOfNeighborChange(p_147459_1_, p_147459_2_ - 1, p_147459_3_, p_147459_4_);
        this.notifyBlockOfNeighborChange(p_147459_1_, p_147459_2_ + 1, p_147459_3_, p_147459_4_);
        this.notifyBlockOfNeighborChange(p_147459_1_, p_147459_2_, p_147459_3_ - 1, p_147459_4_);
        this.notifyBlockOfNeighborChange(p_147459_1_, p_147459_2_, p_147459_3_ + 1, p_147459_4_);
    }
    /**
     * 通知相邻方块发生变化（考虑方向）。
     *
     * @param p_147441_1_ 方块的 x 坐标
     * @param p_147441_2_ 方块的 y 坐标
     * @param p_147441_3_ 方块的 z 坐标
     * @param p_147441_4_ 变化的方块类型
     * @param p_147441_5_ 方向标志，决定哪些方块需要通知
     */
    public void notifyBlocksOfNeighborChange(int p_147441_1_, int p_147441_2_, int p_147441_3_, Block p_147441_4_, int p_147441_5_)
    {
        if (p_147441_5_ != 4)
        {
            this.notifyBlockOfNeighborChange(p_147441_1_ - 1, p_147441_2_, p_147441_3_, p_147441_4_);
        }

        if (p_147441_5_ != 5)
        {
            this.notifyBlockOfNeighborChange(p_147441_1_ + 1, p_147441_2_, p_147441_3_, p_147441_4_);
        }

        if (p_147441_5_ != 0)
        {
            this.notifyBlockOfNeighborChange(p_147441_1_, p_147441_2_ - 1, p_147441_3_, p_147441_4_);
        }

        if (p_147441_5_ != 1)
        {
            this.notifyBlockOfNeighborChange(p_147441_1_, p_147441_2_ + 1, p_147441_3_, p_147441_4_);
        }

        if (p_147441_5_ != 2)
        {
            this.notifyBlockOfNeighborChange(p_147441_1_, p_147441_2_, p_147441_3_ - 1, p_147441_4_);
        }

        if (p_147441_5_ != 3)
        {
            this.notifyBlockOfNeighborChange(p_147441_1_, p_147441_2_, p_147441_3_ + 1, p_147441_4_);
        }
    }

    /**
     * 通知指定坐标的方块其邻居方块发生变化。
     *
     * @param p_147460_1_ 方块的 x 坐标
     * @param p_147460_2_ 方块的 y 坐标
     * @param p_147460_3_ 方块的 z 坐标
     * @param p_147460_4_ 变化的方块类型
     */
    public void notifyBlockOfNeighborChange(int p_147460_1_, int p_147460_2_, int p_147460_3_, final Block p_147460_4_)
    {
        if (!this.isRemote)
        {
            Block block = this.getBlock(p_147460_1_, p_147460_2_, p_147460_3_);

            try
            {
                block.onNeighborBlockChange(this, p_147460_1_, p_147460_2_, p_147460_3_, p_147460_4_);
            }
            catch (Throwable throwable1)
            {
                CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception while updating neighbours");
                CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being updated");
                int l;

                try
                {
                    l = this.getBlockMetadata(p_147460_1_, p_147460_2_, p_147460_3_);
                }
                catch (Throwable throwable)
                {
                    l = -1;
                }

                crashreportcategory.addCrashSectionCallable("Source block type", new Callable()
                {
                    private static final String __OBFID = "CL_00000142";
                    public String call()
                    {
                        try
                        {
                            return String.format("ID #%d (%s // %s)", new Object[] {Integer.valueOf(Block.getIdFromBlock(p_147460_4_)), p_147460_4_.getUnlocalizedName(), p_147460_4_.getClass().getCanonicalName()});
                        }
                        catch (Throwable throwable2)
                        {
                            return "ID #" + Block.getIdFromBlock(p_147460_4_);
                        }
                    }
                });
                CrashReportCategory.func_147153_a(crashreportcategory, p_147460_1_, p_147460_2_, p_147460_3_, block, l);
                throw new ReportedException(crashreport);
            }
        }
    }

    /**
     * 返回给定方块是否在本次 tick 中会接收到计划的 tick。
     *
     * @param p_147477_1_ 方块的 x 坐标
     * @param p_147477_2_ 方块的 y 坐标
     * @param p_147477_3_ 方块的 z 坐标
     * @param p_147477_4_ 方块对象
     * @return 如果方块在本次 tick 中会接收到计划的 tick，则返回 true，否则返回 false
     */
    public boolean isBlockTickScheduledThisTick(int p_147477_1_, int p_147477_2_, int p_147477_3_, Block p_147477_4_)
    {
        return false;
    }

    /**
     * 检查指定方块是否能够看到天空。
     *
     * @param p_72937_1_ 方块的 x 坐标
     * @param p_72937_2_ 方块的 y 坐标
     * @param p_72937_3_ 方块的 z 坐标
     * @return 如果方块能够看到天空，则返回 true，否则返回 false
     */
    public boolean canBlockSeeTheSky(int p_72937_1_, int p_72937_2_, int p_72937_3_)
    {
        return this.getChunkFromChunkCoords(p_72937_1_ >> 4, p_72937_3_ >> 4).canBlockSeeTheSky(p_72937_1_ & 15, p_72937_2_, p_72937_3_ & 15);
    }

    /**
     * 获取指定坐标的方块的完整光照值，不检查是否为普通方块。
     *
     * @param p_72883_1_ 方块的 x 坐标
     * @param p_72883_2_ 方块的 y 坐标
     * @param p_72883_3_ 方块的 z 坐标
     * @return 方块的完整光照值
     */
    public int getFullBlockLightValue(int p_72883_1_, int p_72883_2_, int p_72883_3_)
    {
        if (p_72883_2_ < 0)
        {
            return 0;
        }
        else
        {
            if (p_72883_2_ >= 256)
            {
                p_72883_2_ = 255;
            }

            return this.getChunkFromChunkCoords(p_72883_1_ >> 4, p_72883_3_ >> 4).getBlockLightValue(p_72883_1_ & 15, p_72883_2_, p_72883_3_ & 15, 0);
        }
    }

    /**
     * 获取指定坐标的方块的光照值。
     *
     * @param p_72957_1_ 方块的 x 坐标
     * @param p_72957_2_ 方块的 y 坐标
     * @param p_72957_3_ 方块的 z 坐标
     * @return 方块的光照值
     */
    public int getBlockLightValue(int p_72957_1_, int p_72957_2_, int p_72957_3_)
    {
        return this.getBlockLightValue_do(p_72957_1_, p_72957_2_, p_72957_3_, true);
    }

    /**
     * 获取指定坐标的方块的光照值。这个实际函数获取光照值，并有一个布尔标志来指示是否为半方块，
     * 以获取直接相邻方块（左、右、前、后、上）的最大光照值。
     *
     * @param p_72849_1_ 方块的 x 坐标
     * @param p_72849_2_ 方块的 y 坐标
     * @param p_72849_3_ 方块的 z 坐标
     * @param p_72849_4_ 是否为半方块标志
     * @return 方块的光照值
     */
    public int getBlockLightValue_do(int p_72849_1_, int p_72849_2_, int p_72849_3_, boolean p_72849_4_)
    {
        if (p_72849_1_ >= -30000000 && p_72849_3_ >= -30000000 && p_72849_1_ < 30000000 && p_72849_3_ < 30000000)
        {
            if (p_72849_4_ && this.getBlock(p_72849_1_, p_72849_2_, p_72849_3_).getUseNeighborBrightness())
            {
                int l1 = this.getBlockLightValue_do(p_72849_1_, p_72849_2_ + 1, p_72849_3_, false);
                int l = this.getBlockLightValue_do(p_72849_1_ + 1, p_72849_2_, p_72849_3_, false);
                int i1 = this.getBlockLightValue_do(p_72849_1_ - 1, p_72849_2_, p_72849_3_, false);
                int j1 = this.getBlockLightValue_do(p_72849_1_, p_72849_2_, p_72849_3_ + 1, false);
                int k1 = this.getBlockLightValue_do(p_72849_1_, p_72849_2_, p_72849_3_ - 1, false);

                if (l > l1)
                {
                    l1 = l;
                }

                if (i1 > l1)
                {
                    l1 = i1;
                }

                if (j1 > l1)
                {
                    l1 = j1;
                }

                if (k1 > l1)
                {
                    l1 = k1;
                }

                return l1;
            }
            else if (p_72849_2_ < 0)
            {
                return 0;
            }
            else
            {
                if (p_72849_2_ >= 256)
                {
                    p_72849_2_ = 255;
                }

                Chunk chunk = this.getChunkFromChunkCoords(p_72849_1_ >> 4, p_72849_3_ >> 4);
                p_72849_1_ &= 15;
                p_72849_3_ &= 15;
                return chunk.getBlockLightValue(p_72849_1_, p_72849_2_, p_72849_3_, this.skylightSubtracted);
            }
        }
        else
        {
            return 15;
        }
    }

    /**
     * 返回指定 x, z 坐标的高度值，即该坐标上的方块的 y 坐标。
     *
     * @param p_72976_1_ x 坐标
     * @param p_72976_2_ z 坐标
     * @return 指定坐标上的高度值
     */
    public int getHeightValue(int p_72976_1_, int p_72976_2_)
    {
        if (p_72976_1_ >= -30000000 && p_72976_2_ >= -30000000 && p_72976_1_ < 30000000 && p_72976_2_ < 30000000)
        {
            if (!this.chunkExists(p_72976_1_ >> 4, p_72976_2_ >> 4))
            {
                return 0;
            }
            else
            {
                Chunk chunk = this.getChunkFromChunkCoords(p_72976_1_ >> 4, p_72976_2_ >> 4);
                return chunk.getHeightValue(p_72976_1_ & 15, p_72976_2_ & 15);
            }
        }
        else
        {
            return 64;
        }
    }

    /**
     * 获取给定区块的高度图最小值字段，如果区块未加载，则返回 0。坐标单位为块。
     *
     * @param p_82734_1_ 区块的 x 坐标
     * @param p_82734_2_ 区块的 z 坐标
     * @return 区块的高度图最小值，如果区块未加载，则返回 0
     */
    public int getChunkHeightMapMinimum(int p_82734_1_, int p_82734_2_)
    {
        if (p_82734_1_ >= -30000000 && p_82734_2_ >= -30000000 && p_82734_1_ < 30000000 && p_82734_2_ < 30000000)
        {
            if (!this.chunkExists(p_82734_1_ >> 4, p_82734_2_ >> 4))
            {
                return 0;
            }
            else
            {
                Chunk chunk = this.getChunkFromChunkCoords(p_82734_1_ >> 4, p_82734_2_ >> 4);
                return chunk.heightMapMinimum;
            }
        }
        else
        {
            return 64;
        }
    }

    /**
     * 获取天空光照的亮度值。SkyBlock.Sky 亮度为纯白色（假设通过颜色计算），并依赖于白天时间。
     * SkyBlock.Block 亮度为黄色，并且独立于时间。
     *
     * @param p_72925_1_ 光照类型（天空或方块）
     * @param p_72925_2_ 坐标的 x 值
     * @param p_72925_3_ 坐标的 y 值
     * @param p_72925_4_ 坐标的 z 值
     * @return 指定坐标的光照亮度值
     */
    @SideOnly(Side.CLIENT)
    public int getSkyBlockTypeBrightness(EnumSkyBlock p_72925_1_, int p_72925_2_, int p_72925_3_, int p_72925_4_)
    {
        if (this.provider.hasNoSky && p_72925_1_ == EnumSkyBlock.Sky)
        {
            return 0;
        }
        else
        {
            if (p_72925_3_ < 0)
            {
                p_72925_3_ = 0;
            }

            if (p_72925_3_ >= 256)
            {
                return p_72925_1_.defaultLightValue;
            }
            else if (p_72925_2_ >= -30000000 && p_72925_4_ >= -30000000 && p_72925_2_ < 30000000 && p_72925_4_ < 30000000)
            {
                int l = p_72925_2_ >> 4;
                int i1 = p_72925_4_ >> 4;

                if (!this.chunkExists(l, i1))
                {
                    return p_72925_1_.defaultLightValue;
                }
                else if (this.getBlock(p_72925_2_, p_72925_3_, p_72925_4_).getUseNeighborBrightness())
                {
                    int j2 = this.getSavedLightValue(p_72925_1_, p_72925_2_, p_72925_3_ + 1, p_72925_4_);
                    int j1 = this.getSavedLightValue(p_72925_1_, p_72925_2_ + 1, p_72925_3_, p_72925_4_);
                    int k1 = this.getSavedLightValue(p_72925_1_, p_72925_2_ - 1, p_72925_3_, p_72925_4_);
                    int l1 = this.getSavedLightValue(p_72925_1_, p_72925_2_, p_72925_3_, p_72925_4_ + 1);
                    int i2 = this.getSavedLightValue(p_72925_1_, p_72925_2_, p_72925_3_, p_72925_4_ - 1);

                    if (j1 > j2)
                    {
                        j2 = j1;
                    }

                    if (k1 > j2)
                    {
                        j2 = k1;
                    }

                    if (l1 > j2)
                    {
                        j2 = l1;
                    }

                    if (i2 > j2)
                    {
                        j2 = i2;
                    }

                    return j2;
                }
                else
                {
                    Chunk chunk = this.getChunkFromChunkCoords(l, i1);
                    return chunk.getSavedLightValue(p_72925_1_, p_72925_2_ & 15, p_72925_3_, p_72925_4_ & 15);
                }
            }
            else
            {
                return p_72925_1_.defaultLightValue;
            }
        }
    }

    /**
     * 返回保存的光照值，不考虑时间。根据 enumSkyBlock 参数，查看天空光照图或方块光照图。
     *
     * @param p_72972_1_ 光照类型（天空或方块）
     * @param p_72972_2_ 坐标的 x 值
     * @param p_72972_3_ 坐标的 y 值
     * @param p_72972_4_ 坐标的 z 值
     * @return 指定坐标的保存光照值
     */
    public int getSavedLightValue(EnumSkyBlock p_72972_1_, int p_72972_2_, int p_72972_3_, int p_72972_4_)
    {
        if (p_72972_3_ < 0)
        {
            p_72972_3_ = 0;
        }

        if (p_72972_3_ >= 256)
        {
            p_72972_3_ = 255;
        }

        if (p_72972_2_ >= -30000000 && p_72972_4_ >= -30000000 && p_72972_2_ < 30000000 && p_72972_4_ < 30000000)
        {
            int l = p_72972_2_ >> 4;
            int i1 = p_72972_4_ >> 4;

            if (!this.chunkExists(l, i1))
            {
                return p_72972_1_.defaultLightValue;
            }
            else
            {
                Chunk chunk = this.getChunkFromChunkCoords(l, i1);
                return chunk.getSavedLightValue(p_72972_1_, p_72972_2_ & 15, p_72972_3_, p_72972_4_ & 15);
            }
        }
        else
        {
            return p_72972_1_.defaultLightValue;
        }
    }

    /**
     * 根据传入的 enumSkyBlock（天空或方块）将光照值设置到天空光照图或方块光照图中。
     *
     * @param p_72915_1_ 光照类型（天空或方块）
     * @param p_72915_2_ 坐标的 x 值
     * @param p_72915_3_ 坐标的 y 值
     * @param p_72915_4_ 坐标的 z 值
     * @param p_72915_5_ 光照值
     */
    public void setLightValue(EnumSkyBlock p_72915_1_, int p_72915_2_, int p_72915_3_, int p_72915_4_, int p_72915_5_)
    {
        if (p_72915_2_ >= -30000000 && p_72915_4_ >= -30000000 && p_72915_2_ < 30000000 && p_72915_4_ < 30000000)
        {
            if (p_72915_3_ >= 0)
            {
                if (p_72915_3_ < 256)
                {
                    if (this.chunkExists(p_72915_2_ >> 4, p_72915_4_ >> 4))
                    {
                        Chunk chunk = this.getChunkFromChunkCoords(p_72915_2_ >> 4, p_72915_4_ >> 4);
                        chunk.setLightValue(p_72915_1_, p_72915_2_ & 15, p_72915_3_, p_72915_4_ & 15, p_72915_5_);

                        for (int i1 = 0; i1 < this.worldAccesses.size(); ++i1)
                        {
                            ((IWorldAccess)this.worldAccesses.get(i1)).markBlockForRenderUpdate(p_72915_2_, p_72915_3_, p_72915_4_);
                        }
                    }
                }
            }
        }
    }

    public void func_147479_m(int p_147479_1_, int p_147479_2_, int p_147479_3_)
    {
        for (int l = 0; l < this.worldAccesses.size(); ++l)
        {
            ((IWorldAccess)this.worldAccesses.get(l)).markBlockForRenderUpdate(p_147479_1_, p_147479_2_, p_147479_3_);
        }
    }

    /**
     * 获取指定坐标的光照亮度。这个方法在 1.8 版本中用于渲染光照。
     *
     * @param p_72802_1_ 坐标的 x 值
     * @param p_72802_2_ 坐标的 y 值
     * @param p_72802_3_ 坐标的 z 值
     * @param p_72802_4_ 方块光照值
     * @return 组合的光照亮度值
     */
    @SideOnly(Side.CLIENT)
    public int getLightBrightnessForSkyBlocks(int p_72802_1_, int p_72802_2_, int p_72802_3_, int p_72802_4_)
    {
        int i1 = this.getSkyBlockTypeBrightness(EnumSkyBlock.Sky, p_72802_1_, p_72802_2_, p_72802_3_);
        int j1 = this.getSkyBlockTypeBrightness(EnumSkyBlock.Block, p_72802_1_, p_72802_2_, p_72802_3_);

        if (j1 < p_72802_4_)
        {
            j1 = p_72802_4_;
        }

        return i1 << 20 | j1 << 4;
    }

    /**
     * 返回方块的亮度值，根据查找表中的光照值（光照值在亮度表中不是线性的）。
     *
     * @param p_72801_1_ 坐标的 x 值
     * @param p_72801_2_ 坐标的 y 值
     * @param p_72801_3_ 坐标的 z 值
     * @return 方块的亮度值
     */
    public float getLightBrightness(int p_72801_1_, int p_72801_2_, int p_72801_3_)
    {
        return this.provider.lightBrightnessTable[this.getBlockLightValue(p_72801_1_, p_72801_2_, p_72801_3_)];
    }

    /**
     * 检查是否是白天，通过查看天空光照减去的光照值是否小于 4 来判断。
     *
     * @return 如果是白天则返回 true，否则返回 false
     */
    public boolean isDaytime()
    {
        return provider.isDaytime();
    }

    /**
     * 在世界中的所有块上执行射线追踪，排除液体。
     *
     * @param p_72933_1_ 射线起点
     * @param p_72933_2_ 射线终点
     * @return 射线追踪的结果
     */
    public MovingObjectPosition rayTraceBlocks(Vec3 p_72933_1_, Vec3 p_72933_2_)
    {
        return this.func_147447_a(p_72933_1_, p_72933_2_, false, false, false);
    }

    /**
     * 在世界中的所有块上执行射线追踪，并且可以选择是否包括液体。
     *
     * @param p_72901_1_ 射线起点
     * @param p_72901_2_ 射线终点
     * @param p_72901_3_ 是否包括液体
     * @return 射线追踪的结果
     */
    public MovingObjectPosition rayTraceBlocks(Vec3 p_72901_1_, Vec3 p_72901_2_, boolean p_72901_3_)
    {
        return this.func_147447_a(p_72901_1_, p_72901_2_, p_72901_3_, false, false);
    }
    /**
     * 执行射线追踪以检测与方块的碰撞。可以选择是否包括液体，并在碰撞时返回相应的结果。
     *
     * @param p_147447_1_ 射线的起点
     * @param p_147447_2_ 射线的终点
     * @param p_147447_3_ 是否检测碰撞
     * @param p_147447_4_ 是否检测方块的边界框
     * @param p_147447_5_ 是否在没有碰撞时返回最近的方块信息
     * @return 射线与方块的碰撞结果，或者如果没有碰撞但要求返回最近方块的信息时返回该信息
     */
    public MovingObjectPosition func_147447_a(Vec3 p_147447_1_, Vec3 p_147447_2_, boolean p_147447_3_, boolean p_147447_4_, boolean p_147447_5_)
    {
        if (!Double.isNaN(p_147447_1_.xCoord) && !Double.isNaN(p_147447_1_.yCoord) && !Double.isNaN(p_147447_1_.zCoord))
        {
            if (!Double.isNaN(p_147447_2_.xCoord) && !Double.isNaN(p_147447_2_.yCoord) && !Double.isNaN(p_147447_2_.zCoord))
            {
                int i = MathHelper.floor_double(p_147447_2_.xCoord);
                int j = MathHelper.floor_double(p_147447_2_.yCoord);
                int k = MathHelper.floor_double(p_147447_2_.zCoord);
                int l = MathHelper.floor_double(p_147447_1_.xCoord);
                int i1 = MathHelper.floor_double(p_147447_1_.yCoord);
                int j1 = MathHelper.floor_double(p_147447_1_.zCoord);
                Block block = this.getBlock(l, i1, j1);
                int k1 = this.getBlockMetadata(l, i1, j1);

                if ((!p_147447_4_ || block.getCollisionBoundingBoxFromPool(this, l, i1, j1) != null) && block.canCollideCheck(k1, p_147447_3_))
                {
                    MovingObjectPosition movingobjectposition = block.collisionRayTrace(this, l, i1, j1, p_147447_1_, p_147447_2_);

                    if (movingobjectposition != null)
                    {
                        return movingobjectposition;
                    }
                }

                MovingObjectPosition movingobjectposition2 = null;
                k1 = 200;

                while (k1-- >= 0)
                {
                    if (Double.isNaN(p_147447_1_.xCoord) || Double.isNaN(p_147447_1_.yCoord) || Double.isNaN(p_147447_1_.zCoord))
                    {
                        return null;
                    }

                    if (l == i && i1 == j && j1 == k)
                    {
                        return p_147447_5_ ? movingobjectposition2 : null;
                    }

                    boolean flag6 = true;
                    boolean flag3 = true;
                    boolean flag4 = true;
                    double d0 = 999.0D;
                    double d1 = 999.0D;
                    double d2 = 999.0D;

                    if (i > l)
                    {
                        d0 = (double)l + 1.0D;
                    }
                    else if (i < l)
                    {
                        d0 = (double)l + 0.0D;
                    }
                    else
                    {
                        flag6 = false;
                    }

                    if (j > i1)
                    {
                        d1 = (double)i1 + 1.0D;
                    }
                    else if (j < i1)
                    {
                        d1 = (double)i1 + 0.0D;
                    }
                    else
                    {
                        flag3 = false;
                    }

                    if (k > j1)
                    {
                        d2 = (double)j1 + 1.0D;
                    }
                    else if (k < j1)
                    {
                        d2 = (double)j1 + 0.0D;
                    }
                    else
                    {
                        flag4 = false;
                    }

                    double d3 = 999.0D;
                    double d4 = 999.0D;
                    double d5 = 999.0D;
                    double d6 = p_147447_2_.xCoord - p_147447_1_.xCoord;
                    double d7 = p_147447_2_.yCoord - p_147447_1_.yCoord;
                    double d8 = p_147447_2_.zCoord - p_147447_1_.zCoord;

                    if (flag6)
                    {
                        d3 = (d0 - p_147447_1_.xCoord) / d6;
                    }

                    if (flag3)
                    {
                        d4 = (d1 - p_147447_1_.yCoord) / d7;
                    }

                    if (flag4)
                    {
                        d5 = (d2 - p_147447_1_.zCoord) / d8;
                    }

                    boolean flag5 = false;
                    byte b0;

                    if (d3 < d4 && d3 < d5)
                    {
                        if (i > l)
                        {
                            b0 = 4;
                        }
                        else
                        {
                            b0 = 5;
                        }

                        p_147447_1_.xCoord = d0;
                        p_147447_1_.yCoord += d7 * d3;
                        p_147447_1_.zCoord += d8 * d3;
                    }
                    else if (d4 < d5)
                    {
                        if (j > i1)
                        {
                            b0 = 0;
                        }
                        else
                        {
                            b0 = 1;
                        }

                        p_147447_1_.xCoord += d6 * d4;
                        p_147447_1_.yCoord = d1;
                        p_147447_1_.zCoord += d8 * d4;
                    }
                    else
                    {
                        if (k > j1)
                        {
                            b0 = 2;
                        }
                        else
                        {
                            b0 = 3;
                        }

                        p_147447_1_.xCoord += d6 * d5;
                        p_147447_1_.yCoord += d7 * d5;
                        p_147447_1_.zCoord = d2;
                    }

                    Vec3 vec32 = Vec3.createVectorHelper(p_147447_1_.xCoord, p_147447_1_.yCoord, p_147447_1_.zCoord);
                    l = (int)(vec32.xCoord = (double)MathHelper.floor_double(p_147447_1_.xCoord));

                    if (b0 == 5)
                    {
                        --l;
                        ++vec32.xCoord;
                    }

                    i1 = (int)(vec32.yCoord = (double)MathHelper.floor_double(p_147447_1_.yCoord));

                    if (b0 == 1)
                    {
                        --i1;
                        ++vec32.yCoord;
                    }

                    j1 = (int)(vec32.zCoord = (double)MathHelper.floor_double(p_147447_1_.zCoord));

                    if (b0 == 3)
                    {
                        --j1;
                        ++vec32.zCoord;
                    }

                    Block block1 = this.getBlock(l, i1, j1);
                    int l1 = this.getBlockMetadata(l, i1, j1);

                    if (!p_147447_4_ || block1.getCollisionBoundingBoxFromPool(this, l, i1, j1) != null)
                    {
                        if (block1.canCollideCheck(l1, p_147447_3_))
                        {
                            MovingObjectPosition movingobjectposition1 = block1.collisionRayTrace(this, l, i1, j1, p_147447_1_, p_147447_2_);

                            if (movingobjectposition1 != null)
                            {
                                return movingobjectposition1;
                            }
                        }
                        else
                        {
                            movingobjectposition2 = new MovingObjectPosition(l, i1, j1, b0, p_147447_1_, false);
                        }
                    }
                }

                return p_147447_5_ ? movingobjectposition2 : null;
            }
            else
            {
                return null;
            }
        }
        else
        {
            return null;
        }
    }

    /**
     * 在实体位置播放声音。
     *
     * @param p_72956_1_ 实体
     * @param p_72956_2_ 声音名称
     * @param p_72956_3_ 音量（相对于 1.0）
     * @param p_72956_4_ 频率（或音调，相对于 1.0）
     */
    public void playSoundAtEntity(Entity p_72956_1_, String p_72956_2_, float p_72956_3_, float p_72956_4_)
    {
        PlaySoundAtEntityEvent event = new PlaySoundAtEntityEvent(p_72956_1_, p_72956_2_, p_72956_3_, p_72956_4_);
        if (MinecraftForge.EVENT_BUS.post(event))
        {
            return;
        }
        p_72956_2_ = event.name;
        for (int i = 0; i < this.worldAccesses.size(); ++i)
        {
            ((IWorldAccess)this.worldAccesses.get(i)).playSound(p_72956_2_, p_72956_1_.posX, p_72956_1_.posY - (double)p_72956_1_.yOffset, p_72956_1_.posZ, p_72956_3_, p_72956_4_);
        }
    }

    /**
     * 向所有接近的玩家播放声音，除了指定的玩家。
     *
     * @param p_85173_1_ 排除的玩家
     * @param p_85173_2_ 声音的名称
     * @param p_85173_3_ 音量
     * @param p_85173_4_ 音调
     */
    public void playSoundToNearExcept(EntityPlayer p_85173_1_, String p_85173_2_, float p_85173_3_, float p_85173_4_)
    {
        PlaySoundAtEntityEvent event = new PlaySoundAtEntityEvent(p_85173_1_, p_85173_2_, p_85173_3_, p_85173_4_);
        if (MinecraftForge.EVENT_BUS.post(event))
        {
            return;
        }
        p_85173_2_ = event.name;
        for (int i = 0; i < this.worldAccesses.size(); ++i)
        {
            ((IWorldAccess)this.worldAccesses.get(i)).playSoundToNearExcept(p_85173_1_, p_85173_2_, p_85173_1_.posX, p_85173_1_.posY - (double)p_85173_1_.yOffset, p_85173_1_.posZ, p_85173_3_, p_85173_4_);
        }
    }

    /**
     * 播放一个声音效果。许多参数用于此函数。一个经典调用示例如下：
     * (double)i + 0.5D, (double)j + 0.5D, (double)k + 0.5D, 'random.door_open', 1.0F, world.rand.nextFloat() * 0.1F + 0.9F，其中i, j, k是块的位置。
     *
     * @param x      X坐标
     * @param y      Y坐标
     * @param z      Z坐标
     * @param soundName 声音的名称
     * @param volume  音量
     * @param pitch   音调
     */
    public void playSoundEffect(double x, double y, double z, String soundName, float volume, float pitch)
    {
        for (int i = 0; i < this.worldAccesses.size(); ++i)
        {
            ((IWorldAccess)this.worldAccesses.get(i)).playSound(soundName, x, y, z, volume, pitch);
        }
    }

    /**
     * 播放声音，所有参数传递给minecraftInstance.sndManager.playSound。
     *
     * @param x           X坐标
     * @param y           Y坐标
     * @param z           Z坐标
     * @param soundName   声音的名称
     * @param volume      音量
     * @param pitch       音调
     * @param distanceDelay 是否距离延迟
     */
    public void playSound(double x, double y, double z, String soundName, float volume, float pitch, boolean distanceDelay) {}

    /**
     * 在指定坐标播放一张记录。参数：recordName, x, y, z。
     *
     * @param recordName  记录的名称
     * @param x           X坐标
     * @param y           Y坐标
     * @param z           Z坐标
     */
    public void playRecord(String recordName, int x, int y, int z)
    {
        for (int l = 0; l < this.worldAccesses.size(); ++l)
        {
            ((IWorldAccess)this.worldAccesses.get(l)).playRecord(recordName, x, y, z);
        }
    }

    /**
     * 生成一个粒子。参数：particleName, x, y, z, velX, velY, velZ。
     *
     * @param particleName 粒子的名称
     * @param x            X坐标
     * @param y            Y坐标
     * @param z            Z坐标
     * @param velocityX    X轴速度
     * @param velocityY    Y轴速度
     * @param velocityZ    Z轴速度
     */
    public void spawnParticle(String particleName, double x, double y, double z, double velocityX, double velocityY, double velocityZ)
    {
        for (int i = 0; i < this.worldAccesses.size(); ++i)
        {
            ((IWorldAccess)this.worldAccesses.get(i)).spawnParticle(particleName, x, y, z, velocityX, velocityY, velocityZ);
        }
    }

    /**
     * 将一个闪电效果添加到世界中的闪电效果列表中。
     *
     * @param p_72942_1_ 闪电效果实体
     * @return 返回是否成功添加
     */
    public boolean addWeatherEffect(Entity p_72942_1_)
    {
        this.weatherEffects.add(p_72942_1_);
        return true;
    }

    /**
     * 调用此方法将所有实体放置到世界中。
     *
     * @param p_72838_1_ 要放置的实体
     * @return 返回是否成功放置
     */
    public boolean spawnEntityInWorld(Entity p_72838_1_)
    {
        // do not drop any items while restoring blocksnapshots. Prevents dupes
        if (!this.isRemote && (p_72838_1_ == null || (p_72838_1_ instanceof net.minecraft.entity.item.EntityItem && this.restoringBlockSnapshots))) return false;

        int i = MathHelper.floor_double(p_72838_1_.posX / 16.0D);
        int j = MathHelper.floor_double(p_72838_1_.posZ / 16.0D);
        boolean flag = p_72838_1_.forceSpawn;

        if (p_72838_1_ instanceof EntityPlayer)
        {
            flag = true;
        }

        if (!flag && !this.chunkExists(i, j))
        {
            return false;
        }
        else
        {
            if (p_72838_1_ instanceof EntityPlayer)
            {
                EntityPlayer entityplayer = (EntityPlayer)p_72838_1_;
                this.playerEntities.add(entityplayer);
                this.updateAllPlayersSleepingFlag();
            }
            if (MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(p_72838_1_, this)) && !flag) return false;

            this.getChunkFromChunkCoords(i, j).addEntity(p_72838_1_);
            this.loadedEntityList.add(p_72838_1_);
            this.onEntityAdded(p_72838_1_);
            return true;
        }
    }
    /**
     * 当实体被添加到世界时调用。
     *
     * @param p_72923_1_ 被添加的实体
     */
    public void onEntityAdded(Entity p_72923_1_)
    {
        for (int i = 0; i < this.worldAccesses.size(); ++i)
        {
            ((IWorldAccess)this.worldAccesses.get(i)).onEntityCreate(p_72923_1_);
        }
    }
    /**
     * 当实体被从世界中移除时调用。
     *
     * @param p_72847_1_ 被移除的实体
     */
    public void onEntityRemoved(Entity p_72847_1_)
    {
        for (int i = 0; i < this.worldAccesses.size(); ++i)
        {
            ((IWorldAccess)this.worldAccesses.get(i)).onEntityDestroy(p_72847_1_);
        }
    }

    /**
     * 安排实体在下一个tick中移除。将实体标记为死亡以做准备。
     *
     * @param p_72900_1_ 要移除的实体
     */
    public void removeEntity(Entity p_72900_1_)
    {
        if (p_72900_1_.riddenByEntity != null)
        {
            p_72900_1_.riddenByEntity.mountEntity((Entity)null);
        }

        if (p_72900_1_.ridingEntity != null)
        {
            p_72900_1_.mountEntity((Entity)null);
        }

        p_72900_1_.setDead();

        if (p_72900_1_ instanceof EntityPlayer)
        {
            this.playerEntities.remove(p_72900_1_);
            this.updateAllPlayersSleepingFlag();
            this.onEntityRemoved(p_72900_1_);
        }
    }

    /**
     * 请不要使用此方法移除普通实体—使用正常的removeEntity方法。
     *
     * @param p_72973_1_ 要移除的玩家实体
     */
    public void removePlayerEntityDangerously(Entity p_72973_1_)
    {
        p_72973_1_.setDead();

        if (p_72973_1_ instanceof EntityPlayer)
        {
            this.playerEntities.remove(p_72973_1_);
            this.updateAllPlayersSleepingFlag();
        }

        int i = p_72973_1_.chunkCoordX;
        int j = p_72973_1_.chunkCoordZ;

        if (p_72973_1_.addedToChunk && this.chunkExists(i, j))
        {
            this.getChunkFromChunkCoords(i, j).removeEntity(p_72973_1_);
        }

        this.loadedEntityList.remove(p_72973_1_);
        this.onEntityRemoved(p_72973_1_);
    }

    /**
     * 将一个IWorldAccess添加到worldAccesses列表中。
     *
     * @param p_72954_1_ 要添加的IWorldAccess
     */
    public void addWorldAccess(IWorldAccess p_72954_1_)
    {
        this.worldAccesses.add(p_72954_1_);
    }

    /**
     * 返回与aabb碰撞的边界框列表，排除传入实体的碰撞。参数：entity, aabb。
     *
     * @param p_72945_1_ 实体
     * @param p_72945_2_ 包围盒
     * @return 碰撞的边界框列表
     */
    public List<net.minecraft.util.AxisAlignedBB> getCollidingBoundingBoxes(Entity p_72945_1_, AxisAlignedBB p_72945_2_)
    {
        this.collidingBoundingBoxes.clear();
        int i = MathHelper.floor_double(p_72945_2_.minX);
        int j = MathHelper.floor_double(p_72945_2_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_72945_2_.minY);
        int l = MathHelper.floor_double(p_72945_2_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_72945_2_.minZ);
        int j1 = MathHelper.floor_double(p_72945_2_.maxZ + 1.0D);

        for (int k1 = i; k1 < j; ++k1)
        {
            for (int l1 = i1; l1 < j1; ++l1)
            {
                if (this.blockExists(k1, 64, l1))
                {
                    for (int i2 = k - 1; i2 < l; ++i2)
                    {
                        Block block;

                        if (k1 >= -30000000 && k1 < 30000000 && l1 >= -30000000 && l1 < 30000000)
                        {
                            block = this.getBlock(k1, i2, l1);
                        }
                        else
                        {
                            block = Blocks.stone;
                        }

                        block.addCollisionBoxesToList(this, k1, i2, l1, p_72945_2_, this.collidingBoundingBoxes, p_72945_1_);
                    }
                }
            }
        }

        double d0 = 0.25D;
        List list = this.getEntitiesWithinAABBExcludingEntity(p_72945_1_, p_72945_2_.expand(d0, d0, d0));

        for (int j2 = 0; j2 < list.size(); ++j2)
        {
            AxisAlignedBB axisalignedbb1 = ((Entity)list.get(j2)).getBoundingBox();

            if (axisalignedbb1 != null && axisalignedbb1.intersectsWith(p_72945_2_))
            {
                this.collidingBoundingBoxes.add(axisalignedbb1);
            }

            axisalignedbb1 = p_72945_1_.getCollisionBox((Entity)list.get(j2));

            if (axisalignedbb1 != null && axisalignedbb1.intersectsWith(p_72945_2_))
            {
                this.collidingBoundingBoxes.add(axisalignedbb1);
            }
        }

        return this.collidingBoundingBoxes;
    }
    /**
     * 返回与指定的包围盒（AxisAlignedBB）碰撞的所有边界框。
     *
     * 此方法遍历指定包围盒所在区域的所有块，并检查这些块是否与指定的包围盒发生碰撞。它还检查所有在指定包围盒内的实体，以确定它们的碰撞框是否与指定的包围盒发生碰撞。
     *
     * @param p_147461_1_ 要检查碰撞的包围盒
     * @return 与指定包围盒碰撞的所有边界框的列表
     */
    public List<net.minecraft.util.AxisAlignedBB> func_147461_a(AxisAlignedBB p_147461_1_)
    {
        this.collidingBoundingBoxes.clear();
        int i = MathHelper.floor_double(p_147461_1_.minX);
        int j = MathHelper.floor_double(p_147461_1_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_147461_1_.minY);
        int l = MathHelper.floor_double(p_147461_1_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_147461_1_.minZ);
        int j1 = MathHelper.floor_double(p_147461_1_.maxZ + 1.0D);

        for (int k1 = i; k1 < j; ++k1)
        {
            for (int l1 = i1; l1 < j1; ++l1)
            {
                if (this.blockExists(k1, 64, l1))
                {
                    for (int i2 = k - 1; i2 < l; ++i2)
                    {
                        Block block;

                        if (k1 >= -30000000 && k1 < 30000000 && l1 >= -30000000 && l1 < 30000000)
                        {
                            block = this.getBlock(k1, i2, l1);
                        }
                        else
                        {
                            block = Blocks.bedrock;
                        }

                        block.addCollisionBoxesToList(this, k1, i2, l1, p_147461_1_, this.collidingBoundingBoxes, (Entity)null);
                    }
                }
            }
        }

        return this.collidingBoundingBoxes;
    }

    /**
     * 计算当前时间的天空光减去量。
     *
     * 该方法使用提供者（provider）来计算当前时间的太阳亮度因子，然后基于此因子计算天空光的减少量。返回值表示从最大亮度中减去的部分。
     *
     * @param p_72967_1_ 当前的时间参数
     * @return 当前时间的天空光减去量
     */
    public int calculateSkylightSubtracted(float p_72967_1_)
    {
        float f2 = provider.getSunBrightnessFactor(p_72967_1_);
        f2 = 1.0F - f2;
        return (int)(f2 * 11.0F);
    }

    /**
     * 获取当前维度的太阳亮度因子。
     *
     * 该方法返回一个浮点值，表示当前太阳的亮度因子。0.0f 表示没有光照，1.0f 表示最大阳光。这个值通常用于光照检测，比如太阳能电池板。
     *
     * @param p_72967_1_ 当前的时间参数
     * @return 当前的亮度因子
     */
    public float getSunBrightnessFactor(float p_72967_1_)
    {
        float f1 = this.getCelestialAngle(p_72967_1_);
        float f2 = 1.0F - (MathHelper.cos(f1 * (float)Math.PI * 2.0F) * 2.0F + 0.5F);

        if (f2 < 0.0F)
        {
            f2 = 0.0F;
        }

        if (f2 > 1.0F)
        {
            f2 = 1.0F;
        }

        f2 = 1.0F - f2;
        f2 = (float)((double)f2 * (1.0D - (double)(this.getRainStrength(p_72967_1_) * 5.0F) / 16.0D));
        f2 = (float)((double)f2 * (1.0D - (double)(this.getWeightedThunderStrength(p_72967_1_) * 5.0F) / 16.0D));
        return f2;
    }

    /**
     * 从世界访问对象列表中移除指定的世界访问对象。
     *
     * @param p_72848_1_ 要移除的世界访问对象
     */
    public void removeWorldAccess(IWorldAccess p_72848_1_)
    {
        this.worldAccesses.remove(p_72848_1_);
    }

    /**
     * 获取太阳的亮度 - 检查时间、降雨和雷暴。
     *
     * 这个方法返回当前时间的太阳亮度，主要用于客户端显示和计算。
     *
     * @param p_72971_1_ 当前的时间参数
     * @return 当前的太阳亮度
     */
    @SideOnly(Side.CLIENT)
    public float getSunBrightness(float p_72971_1_)
    {
        return provider.getSunBrightness(p_72971_1_);
    }
    /**
     * 获取太阳的亮度，考虑了时间、降雨和雷暴对身体的影响。
     *
     * 这个方法返回当前时间的太阳亮度，基于时间、降雨和雷暴的影响，并且对结果进行了调整以适应视觉效果。
     *
     * @param p_72971_1_ 当前的时间参数
     * @return 当前的太阳亮度，调整后的值
     */
    @SideOnly(Side.CLIENT)
    public float getSunBrightnessBody(float p_72971_1_)
    {
        float f1 = this.getCelestialAngle(p_72971_1_);
        float f2 = 1.0F - (MathHelper.cos(f1 * (float)Math.PI * 2.0F) * 2.0F + 0.2F);

        if (f2 < 0.0F)
        {
            f2 = 0.0F;
        }

        if (f2 > 1.0F)
        {
            f2 = 1.0F;
        }

        f2 = 1.0F - f2;
        f2 = (float)((double)f2 * (1.0D - (double)(this.getRainStrength(p_72971_1_) * 5.0F) / 16.0D));
        f2 = (float)((double)f2 * (1.0D - (double)(this.getWeightedThunderStrength(p_72971_1_) * 5.0F) / 16.0D));
        return f2 * 0.8F + 0.2F;
    }

    /**
     * 计算天空盒的颜色。
     *
     * 该方法返回一个 `Vec3` 对象，表示天空盒的颜色。此方法通常用于客户端渲染效果。
     *
     * @param p_72833_1_ 实体，用于计算颜色的参考
     * @param p_72833_2_ 当前的时间参数
     * @return 计算得到的天空盒颜色
     */
    @SideOnly(Side.CLIENT)
    public Vec3 getSkyColor(Entity p_72833_1_, float p_72833_2_)
    {
        return provider.getSkyColor(p_72833_1_, p_72833_2_);
    }
    /**
     * 计算天空颜色的变化，考虑了天体角度、云彩、雨水、雷暴等因素。
     *
     * 该方法基于当前时间的天体角度计算天空颜色，并考虑了云彩、雨水、雷暴和闪电的影响。
     *
     * @param p_72833_1_ 当前的实体（用于获取位置）
     * @param p_72833_2_ 当前的时间参数
     * @return 计算得到的天空颜色向量
     */
    @SideOnly(Side.CLIENT)
    public Vec3 getSkyColorBody(Entity p_72833_1_, float p_72833_2_)
    {
        float f1 = this.getCelestialAngle(p_72833_2_);
        float f2 = MathHelper.cos(f1 * (float)Math.PI * 2.0F) * 2.0F + 0.5F;

        if (f2 < 0.0F)
        {
            f2 = 0.0F;
        }

        if (f2 > 1.0F)
        {
            f2 = 1.0F;
        }

        int i = MathHelper.floor_double(p_72833_1_.posX);
        int j = MathHelper.floor_double(p_72833_1_.posY);
        int k = MathHelper.floor_double(p_72833_1_.posZ);
        int l = ForgeHooksClient.getSkyBlendColour(this, i, j, k);
        float f4 = (float)(l >> 16 & 255) / 255.0F;
        float f5 = (float)(l >> 8 & 255) / 255.0F;
        float f6 = (float)(l & 255) / 255.0F;
        f4 *= f2;
        f5 *= f2;
        f6 *= f2;
        float f7 = this.getRainStrength(p_72833_2_);
        float f8;
        float f9;

        if (f7 > 0.0F)
        {
            f8 = (f4 * 0.3F + f5 * 0.59F + f6 * 0.11F) * 0.6F;
            f9 = 1.0F - f7 * 0.75F;
            f4 = f4 * f9 + f8 * (1.0F - f9);
            f5 = f5 * f9 + f8 * (1.0F - f9);
            f6 = f6 * f9 + f8 * (1.0F - f9);
        }

        f8 = this.getWeightedThunderStrength(p_72833_2_);

        if (f8 > 0.0F)
        {
            f9 = (f4 * 0.3F + f5 * 0.59F + f6 * 0.11F) * 0.2F;
            float f10 = 1.0F - f8 * 0.75F;
            f4 = f4 * f10 + f9 * (1.0F - f10);
            f5 = f5 * f10 + f9 * (1.0F - f10);
            f6 = f6 * f10 + f9 * (1.0F - f10);
        }

        if (this.lastLightningBolt > 0)
        {
            f9 = (float)this.lastLightningBolt - p_72833_2_;

            if (f9 > 1.0F)
            {
                f9 = 1.0F;
            }

            f9 *= 0.45F;
            f4 = f4 * (1.0F - f9) + 0.8F * f9;
            f5 = f5 * (1.0F - f9) + 0.8F * f9;
            f6 = f6 * (1.0F - f9) + 1.0F * f9;
        }

        return Vec3.createVectorHelper((double)f4, (double)f5, (double)f6);
    }

    /**
     * 获取天体角度。
     *
     * 该方法调用提供者计算天体角度，并基于当前时间参数返回结果。
     *
     * @param p_72826_1_ 当前的时间参数
     * @return 天体角度
     */
    public float getCelestialAngle(float p_72826_1_)
    {
        return this.provider.calculateCelestialAngle(this.worldInfo.getWorldTime(), p_72826_1_);
    }
    /**
     * 获取当前月相。
     *
     * 该方法用于获取当前的月相，并将其用于客户端的渲染效果。
     *
     * @return 当前的月相
     */
    @SideOnly(Side.CLIENT)
    public int getMoonPhase()
    {
        return this.provider.getMoonPhase(this.worldInfo.getWorldTime());
    }

    /**
     * 获取当前月亮相位因子。
     *
     * 该方法返回当前月亮的相位因子，值在 0.0 到 1.0 之间，以 0.25 为步进。
     *
     * @return 当前月亮相位因子
     */
    public float getCurrentMoonPhaseFactor()
    {
        return provider.getCurrentMoonPhaseFactor();
    }
    /**
     * 获取当前月亮相位因子（不使用提供者）。
     *
     * 该方法从月亮相位因子数组中获取当前的月亮相位因子，并返回。
     *
     * @return 当前月亮相位因子
     */
    public float getCurrentMoonPhaseFactorBody()
    {
        return WorldProvider.moonPhaseFactors[this.provider.getMoonPhase(this.worldInfo.getWorldTime())];
    }

    /**
     * 返回天体角度转换为弧度的值。
     *
     * 该方法计算天体角度并将其转换为弧度值（2 * PI）。
     *
     * @param p_72929_1_ 当前的时间参数
     * @return 天体角度的弧度值
     */
    public float getCelestialAngleRadians(float p_72929_1_)
    {
        float f1 = this.getCelestialAngle(p_72929_1_);
        return f1 * (float)Math.PI * 2.0F;
    }
    /**
     * 获取云彩的颜色。
     *
     * 该方法基于当前时间计算云彩的颜色，用于客户端的渲染效果。
     *
     * @param p_72824_1_ 当前的时间参数
     * @return 计算得到的云彩颜色向量
     */
    @SideOnly(Side.CLIENT)
    public Vec3 getCloudColour(float p_72824_1_)
    {
        return provider.drawClouds(p_72824_1_);
    }
    /**
     * 计算云彩的颜色，考虑了天体角度、雨水等因素。
     *
     * 该方法基于当前时间和天体角度计算云彩颜色，并考虑雨水和雷暴的影响。
     *
     * @param p_72824_1_ 当前的时间参数
     * @return 计算得到的云彩颜色向量
     */
    @SideOnly(Side.CLIENT)
    public Vec3 drawCloudsBody(float p_72824_1_)
    {
        float f1 = this.getCelestialAngle(p_72824_1_);
        float f2 = MathHelper.cos(f1 * (float)Math.PI * 2.0F) * 2.0F + 0.5F;

        if (f2 < 0.0F)
        {
            f2 = 0.0F;
        }

        if (f2 > 1.0F)
        {
            f2 = 1.0F;
        }

        float f3 = (float)(this.cloudColour >> 16 & 255L) / 255.0F;
        float f4 = (float)(this.cloudColour >> 8 & 255L) / 255.0F;
        float f5 = (float)(this.cloudColour & 255L) / 255.0F;
        float f6 = this.getRainStrength(p_72824_1_);
        float f7;
        float f8;

        if (f6 > 0.0F)
        {
            f7 = (f3 * 0.3F + f4 * 0.59F + f5 * 0.11F) * 0.6F;
            f8 = 1.0F - f6 * 0.95F;
            f3 = f3 * f8 + f7 * (1.0F - f8);
            f4 = f4 * f8 + f7 * (1.0F - f8);
            f5 = f5 * f8 + f7 * (1.0F - f8);
        }

        f3 *= f2 * 0.9F + 0.1F;
        f4 *= f2 * 0.9F + 0.1F;
        f5 *= f2 * 0.85F + 0.15F;
        f7 = this.getWeightedThunderStrength(p_72824_1_);

        if (f7 > 0.0F)
        {
            f8 = (f3 * 0.3F + f4 * 0.59F + f5 * 0.11F) * 0.2F;
            float f9 = 1.0F - f7 * 0.95F;
            f3 = f3 * f9 + f8 * (1.0F - f9);
            f4 = f4 * f9 + f8 * (1.0F - f9);
            f5 = f5 * f9 + f8 * (1.0F - f9);
        }

        return Vec3.createVectorHelper((double)f3, (double)f4, (double)f5);
    }

    /**
     * 获取雾的颜色。
     *
     * 该方法基于当前时间计算雾的颜色，用于客户端的渲染效果。
     *
     * @param p_72948_1_ 当前的时间参数
     * @return 计算得到的雾颜色向量
     */
    @SideOnly(Side.CLIENT)
    public Vec3 getFogColor(float p_72948_1_)
    {
        float f1 = this.getCelestialAngle(p_72948_1_);
        return this.provider.getFogColor(f1, p_72948_1_);
    }

    /**
     * 获取降水的高度。
     *
     * 该方法计算并返回指定位置的降水高度。
     *
     * @param p_72874_1_ x 坐标
     * @param p_72874_2_ z 坐标
     * @return 指定位置的降水高度
     */
    public int getPrecipitationHeight(int p_72874_1_, int p_72874_2_)
    {
        return this.getChunkFromBlockCoords(p_72874_1_, p_72874_2_).getPrecipitationHeight(p_72874_1_ & 15, p_72874_2_ & 15);
    }

    /**
     * 获取指定位置的最高固体块的 y 坐标。
     *
     * 该方法找到指定 x 和 z 坐标的最高固体块，并返回其 y 坐标。
     *
     * @param p_72825_1_ x 坐标
     * @param p_72825_2_ z 坐标
     * @return 最高固体块的 y 坐标，如果没有找到返回 -1
     */
    public int getTopSolidOrLiquidBlock(int p_72825_1_, int p_72825_2_)
    {
        Chunk chunk = this.getChunkFromBlockCoords(p_72825_1_, p_72825_2_);
        int x = p_72825_1_;
        int z = p_72825_2_;
        int k = chunk.getTopFilledSegment() + 15;
        p_72825_1_ &= 15;

        for (p_72825_2_ &= 15; k > 0; --k)
        {
            Block block = chunk.getBlock(p_72825_1_, k, p_72825_2_);

            if (block.getMaterial().blocksMovement() && block.getMaterial() != Material.leaves && !block.isFoliage(this, x, k, z))
            {
                return k + 1;
            }
        }

        return -1;
    }

    /**
     * 获取星星的亮度。
     *
     * 该方法用于客户端获取星星的亮度，用于渲染效果。
     *
     * @param p_72880_1_ 当前的时间参数
     * @return 星星的亮度
     */
    @SideOnly(Side.CLIENT)
    public float getStarBrightness(float p_72880_1_)
    {
        return provider.getStarBrightness(p_72880_1_);
    }
    /**
     * 计算星星的亮度。
     *
     * 该方法基于天体角度计算星星的亮度。
     *
     * @param par1 当前的时间参数
     * @return 计算得到的星星亮度
     */
    @SideOnly(Side.CLIENT)
    public float getStarBrightnessBody(float par1)
    {
        float f1 = this.getCelestialAngle(par1);
        float f2 = 1.0F - (MathHelper.cos(f1 * (float)Math.PI * 2.0F) * 2.0F + 0.25F);

        if (f2 < 0.0F)
        {
            f2 = 0.0F;
        }

        if (f2 > 1.0F)
        {
            f2 = 1.0F;
        }

        return f2 * f2 * 0.5F;
    }

    /**
     * 计划一个块的更新，指定延迟（通常是更新频率）。
     *
     * 该方法用于计划块的更新操作，但在此实现中并未实际进行任何操作。
     *
     * @param p_147464_1_ x 坐标
     * @param p_147464_2_ y 坐标
     * @param p_147464_3_ z 坐标
     * @param p_147464_4_ 要更新的块
     * @param p_147464_5_ 延迟
     */
    public void scheduleBlockUpdate(int p_147464_1_, int p_147464_2_, int p_147464_3_, Block p_147464_4_, int p_147464_5_) {}
    /**
     * 计划一个块的更新，指定优先级和延迟。
     *
     * 该方法用于计划块的更新操作，并指定更新的优先级和延迟，但在此实现中并未实际进行任何操作。
     *
     * @param p_147454_1_ x 坐标
     * @param p_147454_2_ y 坐标
     * @param p_147454_3_ z 坐标
     * @param p_147454_4_ 要更新的块
     * @param p_147454_5_ 延迟
     * @param p_147454_6_ 优先级
     */
    public void scheduleBlockUpdateWithPriority(int p_147454_1_, int p_147454_2_, int p_147454_3_, Block p_147454_4_, int p_147454_5_, int p_147454_6_) {}
    /**
     * 计划一个块的更新，指定优先级和延迟（另一种实现方式）。
     *
     * 该方法用于计划块的更新操作，并指定更新的优先级和延迟，但在此实现中并未实际进行任何操作。
     *
     * @param p_147446_1_ x 坐标
     * @param p_147446_2_ y 坐标
     * @param p_147446_3_ z 坐标
     * @param p_147446_4_ 要更新的块
     * @param p_147446_5_ 延迟
     * @param p_147446_6_ 优先级
     */
    public void func_147446_b(int p_147446_1_, int p_147446_2_, int p_147446_3_, Block p_147446_4_, int p_147446_5_, int p_147446_6_) {}

    /**
     * 更新（并清理）实体和方块实体。
     *
     * 该方法负责更新世界中的所有实体和方块实体，包括：
     * - 处理天气效果中的实体。
     * - 更新和清理加载的实体和未加载的实体。
     * - 更新和清理方块实体。
     * - 处理待处理的方块实体。
     *
     * 具体步骤如下：
     * 1. **更新天气效果中的实体**：
     *    - 遍历 `weatherEffects` 列表中的所有实体，并调用其 `onUpdate` 方法进行更新。
     *    - 处理可能发生的异常，记录错误报告，并根据配置决定是否移除错误实体。
     *    - 移除已死亡的实体。
     *
     * 2. **移除未加载的实体**：
     *    - 遍历 `unloadedEntityList` 列表中的所有实体。
     *    - 从相应的区块中移除已卸载的实体。
     *    - 调用 `onEntityRemoved` 方法进行清理。
     *    - 清空 `unloadedEntityList`。
     *
     * 3. **更新加载的实体**：
     *    - 遍历 `loadedEntityList` 列表中的所有实体。
     *    - 处理实体的骑乘情况（如果实体的骑乘实体不存在或已经死亡，则解除骑乘关系）。
     *    - 调用 `updateEntity` 方法更新实体。
     *    - 处理可能发生的异常，记录错误报告，并根据配置决定是否移除错误实体。
     *    - 移除已死亡的实体，并调用 `onEntityRemoved` 方法进行清理。
     *
     * 4. **更新方块实体**：
     *    - 遍历 `loadedTileEntityList` 列表中的所有方块实体。
     *    - 调用 `updateEntity` 方法更新方块实体。
     *    - 处理可能发生的异常，记录错误报告，并根据配置决定是否移除错误方块实体。
     *    - 移除无效的方块实体，并从对应的区块中移除。
     *
     * 5. **处理待处理的方块实体**：
     *    - 遍历 `addedTileEntityList` 列表中的所有方块实体。
     *    - 将有效的方块实体添加到 `loadedTileEntityList` 中。
     *    - 对无效的方块实体进行处理，并从对应的区块中移除。
     *    - 清空 `addedTileEntityList`。
     *
     * 此方法通过 `Profiler` 来监控各个阶段的性能，确保系统的性能不会受到影响。
     */
    public void updateEntities()
    {
        this.theProfiler.startSection("entities");
        this.theProfiler.startSection("global");
        int i;
        Entity entity;
        CrashReport crashreport;
        CrashReportCategory crashreportcategory;

        for (i = 0; i < this.weatherEffects.size(); ++i)
        {
            entity = (Entity)this.weatherEffects.get(i);

            try
            {
                ++entity.ticksExisted;
                entity.onUpdate();
            }
            catch (Throwable throwable2)
            {
                crashreport = CrashReport.makeCrashReport(throwable2, "Ticking entity");
                crashreportcategory = crashreport.makeCategory("Entity being ticked");

                if (entity == null)
                {
                    crashreportcategory.addCrashSection("Entity", "~~NULL~~");
                }
                else
                {
                    entity.addEntityCrashInfo(crashreportcategory);
                }

                if (ForgeModContainer.removeErroringEntities)
                {
                    FMLLog.getLogger().log(org.apache.logging.log4j.Level.ERROR, crashreport.getCompleteReport());
                    removeEntity(entity);
                }
                else
                {
                    throw new ReportedException(crashreport);
                }
            }

            if (entity.isDead)
            {
                this.weatherEffects.remove(i--);
            }
        }

        this.theProfiler.endStartSection("remove");
        this.loadedEntityList.removeAll(this.unloadedEntityList);
        int j;
        int l;

        for (i = 0; i < this.unloadedEntityList.size(); ++i)
        {
            entity = (Entity)this.unloadedEntityList.get(i);
            j = entity.chunkCoordX;
            l = entity.chunkCoordZ;

            if (entity.addedToChunk && this.chunkExists(j, l))
            {
                this.getChunkFromChunkCoords(j, l).removeEntity(entity);
            }
        }

        for (i = 0; i < this.unloadedEntityList.size(); ++i)
        {
            this.onEntityRemoved((Entity)this.unloadedEntityList.get(i));
        }

        this.unloadedEntityList.clear();
        this.theProfiler.endStartSection("regular");

        for (i = 0; i < this.loadedEntityList.size(); ++i)
        {
            entity = (Entity)this.loadedEntityList.get(i);

            if (entity.ridingEntity != null)
            {
                if (!entity.ridingEntity.isDead && entity.ridingEntity.riddenByEntity == entity)
                {
                    continue;
                }

                entity.ridingEntity.riddenByEntity = null;
                entity.ridingEntity = null;
            }

            this.theProfiler.startSection("tick");

            if (!entity.isDead)
            {
                try
                {
                    this.updateEntity(entity);
                }
                catch (Throwable throwable1)
                {
                    crashreport = CrashReport.makeCrashReport(throwable1, "Ticking entity");
                    crashreportcategory = crashreport.makeCategory("Entity being ticked");
                    entity.addEntityCrashInfo(crashreportcategory);

                    if (ForgeModContainer.removeErroringEntities)
                    {
                        FMLLog.getLogger().log(org.apache.logging.log4j.Level.ERROR, crashreport.getCompleteReport());
                        removeEntity(entity);
                    }
                    else
                    {
                        throw new ReportedException(crashreport);
                    }
                }
            }

            this.theProfiler.endSection();
            this.theProfiler.startSection("remove");

            if (entity.isDead)
            {
                j = entity.chunkCoordX;
                l = entity.chunkCoordZ;

                if (entity.addedToChunk && this.chunkExists(j, l))
                {
                    this.getChunkFromChunkCoords(j, l).removeEntity(entity);
                }

                this.loadedEntityList.remove(i--);
                this.onEntityRemoved(entity);
            }

            this.theProfiler.endSection();
        }

        this.theProfiler.endStartSection("blockEntities");
        this.field_147481_N = true;
        Iterator iterator = this.loadedTileEntityList.iterator();

        while (iterator.hasNext())
        {
            TileEntity tileentity = (TileEntity)iterator.next();

            if (!tileentity.isInvalid() && tileentity.hasWorldObj() && this.blockExists(tileentity.xCoord, tileentity.yCoord, tileentity.zCoord))
            {
                try
                {
                    tileentity.updateEntity();
                }
                catch (Throwable throwable)
                {
                    crashreport = CrashReport.makeCrashReport(throwable, "Ticking block entity");
                    crashreportcategory = crashreport.makeCategory("Block entity being ticked");
                    tileentity.func_145828_a(crashreportcategory);
                    if (ForgeModContainer.removeErroringTileEntities)
                    {
                        FMLLog.getLogger().log(org.apache.logging.log4j.Level.ERROR, crashreport.getCompleteReport());
                        tileentity.invalidate();
                        setBlockToAir(tileentity.xCoord, tileentity.yCoord, tileentity.zCoord);
                    }
                    else
                    {
                        throw new ReportedException(crashreport);
                    }
                }
            }

            if (tileentity.isInvalid())
            {
                iterator.remove();

                if (this.chunkExists(tileentity.xCoord >> 4, tileentity.zCoord >> 4))
                {
                    Chunk chunk = this.getChunkFromChunkCoords(tileentity.xCoord >> 4, tileentity.zCoord >> 4);

                    if (chunk != null)
                    {
                        chunk.removeInvalidTileEntity(tileentity.xCoord & 15, tileentity.yCoord, tileentity.zCoord & 15);
                    }
                }
            }
        }

        if (!this.field_147483_b.isEmpty())
        {
            for (Object tile : field_147483_b)
            {
               ((TileEntity)tile).onChunkUnload();
            }
            this.loadedTileEntityList.removeAll(this.field_147483_b);
            this.field_147483_b.clear();
        }

        this.field_147481_N = false;

        this.theProfiler.endStartSection("pendingBlockEntities");

        if (!this.addedTileEntityList.isEmpty())
        {
            for (int k = 0; k < this.addedTileEntityList.size(); ++k)
            {
                TileEntity tileentity1 = (TileEntity)this.addedTileEntityList.get(k);

                if (!tileentity1.isInvalid())
                {
                    if (!this.loadedTileEntityList.contains(tileentity1))
                    {
                        this.loadedTileEntityList.add(tileentity1);
                    }
                }
                else
                {
                    if (this.chunkExists(tileentity1.xCoord >> 4, tileentity1.zCoord >> 4))
                    {
                        Chunk chunk1 = this.getChunkFromChunkCoords(tileentity1.xCoord >> 4, tileentity1.zCoord >> 4);

                        if (chunk1 != null)
                        {
                            chunk1.removeInvalidTileEntity(tileentity1.xCoord & 15, tileentity1.yCoord, tileentity1.zCoord & 15);
                        }
                    }
                }
            }

            this.addedTileEntityList.clear();
        }

        this.theProfiler.endSection();
        this.theProfiler.endSection();
    }
    /**
     * 将给定集合中的所有可以更新的方块实体添加到合适的列表中。
     *
     * 根据 `field_147481_N` 标志，决定将方块实体添加到 `addedTileEntityList` 还是 `loadedTileEntityList`。
     *
     * @param p_147448_1_ 方块实体的集合。
     */
    public void func_147448_a(Collection<net.minecraft.tileentity.TileEntity> p_147448_1_)
    {
        List dest = field_147481_N ? addedTileEntityList : loadedTileEntityList;
        for(TileEntity entity : (Collection<TileEntity>)p_147448_1_)
        {
            if(entity.canUpdate()) dest.add(entity);
        }
    }
    /**
     * 如果实体所在的区块当前已加载，则更新该实体。
     *
     * @param p_72870_1_ 需要更新的实体。
     */
    public void updateEntity(Entity p_72870_1_)
    {
        this.updateEntityWithOptionalForce(p_72870_1_, true);
    }

    /**
     * 根据是否强制更新和实体所在的区块是否已加载来更新实体。
     *
     * @param p_72866_1_ 需要更新的实体。
     * @param p_72866_2_ 是否强制更新实体。
     */
    public void updateEntityWithOptionalForce(Entity p_72866_1_, boolean p_72866_2_)
    {
        int i = MathHelper.floor_double(p_72866_1_.posX);
        int j = MathHelper.floor_double(p_72866_1_.posZ);
        boolean isForced = getPersistentChunks().containsKey(new ChunkCoordIntPair(i >> 4, j >> 4));
        byte b0 = isForced ? (byte)0 : 32;
        boolean canUpdate = !p_72866_2_ || this.checkChunksExist(i - b0, 0, j - b0, i + b0, 0, j + b0);

        if (!canUpdate)
        {
            EntityEvent.CanUpdate event = new EntityEvent.CanUpdate(p_72866_1_);
            MinecraftForge.EVENT_BUS.post(event);
            canUpdate = event.canUpdate;
        }

        if (canUpdate)
        {
            p_72866_1_.lastTickPosX = p_72866_1_.posX;
            p_72866_1_.lastTickPosY = p_72866_1_.posY;
            p_72866_1_.lastTickPosZ = p_72866_1_.posZ;
            p_72866_1_.prevRotationYaw = p_72866_1_.rotationYaw;
            p_72866_1_.prevRotationPitch = p_72866_1_.rotationPitch;

            if (p_72866_2_ && p_72866_1_.addedToChunk)
            {
                ++p_72866_1_.ticksExisted;

                if (p_72866_1_.ridingEntity != null)
                {
                    p_72866_1_.updateRidden();
                }
                else
                {
                    p_72866_1_.onUpdate();
                }
            }

            this.theProfiler.startSection("chunkCheck");

            if (Double.isNaN(p_72866_1_.posX) || Double.isInfinite(p_72866_1_.posX))
            {
                p_72866_1_.posX = p_72866_1_.lastTickPosX;
            }

            if (Double.isNaN(p_72866_1_.posY) || Double.isInfinite(p_72866_1_.posY))
            {
                p_72866_1_.posY = p_72866_1_.lastTickPosY;
            }

            if (Double.isNaN(p_72866_1_.posZ) || Double.isInfinite(p_72866_1_.posZ))
            {
                p_72866_1_.posZ = p_72866_1_.lastTickPosZ;
            }

            if (Double.isNaN((double)p_72866_1_.rotationPitch) || Double.isInfinite((double)p_72866_1_.rotationPitch))
            {
                p_72866_1_.rotationPitch = p_72866_1_.prevRotationPitch;
            }

            if (Double.isNaN((double)p_72866_1_.rotationYaw) || Double.isInfinite((double)p_72866_1_.rotationYaw))
            {
                p_72866_1_.rotationYaw = p_72866_1_.prevRotationYaw;
            }

            int k = MathHelper.floor_double(p_72866_1_.posX / 16.0D);
            int l = MathHelper.floor_double(p_72866_1_.posY / 16.0D);
            int i1 = MathHelper.floor_double(p_72866_1_.posZ / 16.0D);

            if (!p_72866_1_.addedToChunk || p_72866_1_.chunkCoordX != k || p_72866_1_.chunkCoordY != l || p_72866_1_.chunkCoordZ != i1)
            {
                if (p_72866_1_.addedToChunk && this.chunkExists(p_72866_1_.chunkCoordX, p_72866_1_.chunkCoordZ))
                {
                    this.getChunkFromChunkCoords(p_72866_1_.chunkCoordX, p_72866_1_.chunkCoordZ).removeEntityAtIndex(p_72866_1_, p_72866_1_.chunkCoordY);
                }

                if (this.chunkExists(k, i1))
                {
                    p_72866_1_.addedToChunk = true;
                    this.getChunkFromChunkCoords(k, i1).addEntity(p_72866_1_);
                }
                else
                {
                    p_72866_1_.addedToChunk = false;
                }
            }

            this.theProfiler.endSection();

            if (p_72866_2_ && p_72866_1_.addedToChunk && p_72866_1_.riddenByEntity != null)
            {
                if (!p_72866_1_.riddenByEntity.isDead && p_72866_1_.riddenByEntity.ridingEntity == p_72866_1_)
                {
                    this.updateEntity(p_72866_1_.riddenByEntity);
                }
                else
                {
                    p_72866_1_.riddenByEntity.ridingEntity = null;
                    p_72866_1_.riddenByEntity = null;
                }
            }
        }
    }

    /**
     * 返回指定的 AxisAlignedBB 范围内是否没有固体实体。
     *
     * @param p_72855_1_ 需要检查的 AxisAlignedBB 范围。
     * @return 如果范围内没有固体实体，则返回 true；否则返回 false。
     */
    public boolean checkNoEntityCollision(AxisAlignedBB p_72855_1_)
    {
        return this.checkNoEntityCollision(p_72855_1_, (Entity)null);
    }

    /**
     * 返回指定的 AxisAlignedBB 范围内是否没有固体、活跃的实体，排除给定的实体。
     *
     * @param p_72917_1_ 需要检查的 AxisAlignedBB 范围。
     * @param p_72917_2_ 要排除的实体。
     * @return 如果范围内没有固体、活跃的实体，返回 true；否则返回 false。
     */
    public boolean checkNoEntityCollision(AxisAlignedBB p_72917_1_, Entity p_72917_2_)
    {
        List list = this.getEntitiesWithinAABBExcludingEntity((Entity)null, p_72917_1_);

        for (int i = 0; i < list.size(); ++i)
        {
            Entity entity1 = (Entity)list.get(i);

            if (!entity1.isDead && entity1.preventEntitySpawning && entity1 != p_72917_2_)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * 返回指定 AxisAlignedBB 范围内是否存在方块。
     *
     * @param p_72829_1_ 需要检查的 AxisAlignedBB 范围。
     * @return 如果范围内有方块，返回 true；否则返回 false。
     */
    public boolean checkBlockCollision(AxisAlignedBB p_72829_1_)
    {
        int i = MathHelper.floor_double(p_72829_1_.minX);
        int j = MathHelper.floor_double(p_72829_1_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_72829_1_.minY);
        int l = MathHelper.floor_double(p_72829_1_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_72829_1_.minZ);
        int j1 = MathHelper.floor_double(p_72829_1_.maxZ + 1.0D);

        if (p_72829_1_.minX < 0.0D)
        {
            --i;
        }

        if (p_72829_1_.minY < 0.0D)
        {
            --k;
        }

        if (p_72829_1_.minZ < 0.0D)
        {
            --i1;
        }

        for (int k1 = i; k1 < j; ++k1)
        {
            for (int l1 = k; l1 < l; ++l1)
            {
                for (int i2 = i1; i2 < j1; ++i2)
                {
                    Block block = this.getBlock(k1, l1, i2);

                    if (block.getMaterial() != Material.air)
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 返回指定的 AxisAlignedBB 范围内是否存在液体方块。
     *
     * @param p_72953_1_ 需要检查的 AxisAlignedBB 范围。
     * @return 如果范围内有液体方块，返回 true；否则返回 false。
     */
    public boolean isAnyLiquid(AxisAlignedBB p_72953_1_)
    {
        int i = MathHelper.floor_double(p_72953_1_.minX);
        int j = MathHelper.floor_double(p_72953_1_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_72953_1_.minY);
        int l = MathHelper.floor_double(p_72953_1_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_72953_1_.minZ);
        int j1 = MathHelper.floor_double(p_72953_1_.maxZ + 1.0D);

        if (p_72953_1_.minX < 0.0D)
        {
            --i;
        }

        if (p_72953_1_.minY < 0.0D)
        {
            --k;
        }

        if (p_72953_1_.minZ < 0.0D)
        {
            --i1;
        }

        for (int k1 = i; k1 < j; ++k1)
        {
            for (int l1 = k; l1 < l; ++l1)
            {
                for (int i2 = i1; i2 < j1; ++i2)
                {
                    Block block = this.getBlock(k1, l1, i2);

                    if (block.getMaterial().isLiquid())
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }
    /**
     * 返回指定的 AxisAlignedBB 范围内是否存在火焰或熔岩方块，或者是否有方块燃烧。
     *
     * @param p_147470_1_ 需要检查的 AxisAlignedBB 范围。
     * @return 如果范围内有火焰、熔岩方块，或者有方块燃烧，返回 true；否则返回 false。
     */
    public boolean func_147470_e(AxisAlignedBB p_147470_1_)
    {
        int i = MathHelper.floor_double(p_147470_1_.minX);
        int j = MathHelper.floor_double(p_147470_1_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_147470_1_.minY);
        int l = MathHelper.floor_double(p_147470_1_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_147470_1_.minZ);
        int j1 = MathHelper.floor_double(p_147470_1_.maxZ + 1.0D);

        if (this.checkChunksExist(i, k, i1, j, l, j1))
        {
            for (int k1 = i; k1 < j; ++k1)
            {
                for (int l1 = k; l1 < l; ++l1)
                {
                    for (int i2 = i1; i2 < j1; ++i2)
                    {
                        Block block = this.getBlock(k1, l1, i2);

                        if (block == Blocks.fire || block == Blocks.flowing_lava || block == Blocks.lava)
                        {
                            return true;
                        }
                        else
                        {
                            if (block.isBurning(this, k1, l1, i2)) return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * 处理物体在水中的加速度。如果方法返回 true，说明物体在水中加速了。
     *
     * @param p_72918_1_ 需要处理的 AxisAlignedBB 范围。
     * @param p_72918_2_ 物质类型（如水或熔岩）。
     * @param p_72918_3_ 需要加速的实体。
     * @return 如果实体在指定的范围内接触到指定的物质并且加速了，返回 true；否则返回 false。
     */
    public boolean handleMaterialAcceleration(AxisAlignedBB p_72918_1_, Material p_72918_2_, Entity p_72918_3_)
    {
        int i = MathHelper.floor_double(p_72918_1_.minX);
        int j = MathHelper.floor_double(p_72918_1_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_72918_1_.minY);
        int l = MathHelper.floor_double(p_72918_1_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_72918_1_.minZ);
        int j1 = MathHelper.floor_double(p_72918_1_.maxZ + 1.0D);

        if (!this.checkChunksExist(i, k, i1, j, l, j1))
        {
            return false;
        }
        else
        {
            boolean flag = false;
            Vec3 vec3 = Vec3.createVectorHelper(0.0D, 0.0D, 0.0D);

            for (int k1 = i; k1 < j; ++k1)
            {
                for (int l1 = k; l1 < l; ++l1)
                {
                    for (int i2 = i1; i2 < j1; ++i2)
                    {
                        Block block = this.getBlock(k1, l1, i2);

                        if (block.getMaterial() == p_72918_2_)
                        {
                            double d0 = (double)((float)(l1 + 1) - BlockLiquid.getLiquidHeightPercent(this.getBlockMetadata(k1, l1, i2)));

                            if ((double)l >= d0)
                            {
                                flag = true;
                                block.velocityToAddToEntity(this, k1, l1, i2, p_72918_3_, vec3);
                            }
                        }
                    }
                }
            }

            if (vec3.lengthVector() > 0.0D && p_72918_3_.isPushedByWater())
            {
                vec3 = vec3.normalize();
                double d1 = 0.014D;
                p_72918_3_.motionX += vec3.xCoord * d1;
                p_72918_3_.motionY += vec3.yCoord * d1;
                p_72918_3_.motionZ += vec3.zCoord * d1;
            }

            return flag;
        }
    }

    /**
     * 返回指定的 AxisAlignedBB 范围内是否包含指定的物质。
     *
     * @param p_72875_1_ 需要检查的 AxisAlignedBB 范围。
     * @param p_72875_2_ 需要检查的物质类型。
     * @return 如果范围内包含指定的物质，返回 true；否则返回 false。
     */
    public boolean isMaterialInBB(AxisAlignedBB p_72875_1_, Material p_72875_2_)
    {
        int i = MathHelper.floor_double(p_72875_1_.minX);
        int j = MathHelper.floor_double(p_72875_1_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_72875_1_.minY);
        int l = MathHelper.floor_double(p_72875_1_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_72875_1_.minZ);
        int j1 = MathHelper.floor_double(p_72875_1_.maxZ + 1.0D);

        for (int k1 = i; k1 < j; ++k1)
        {
            for (int l1 = k; l1 < l; ++l1)
            {
                for (int i2 = i1; i2 < j1; ++i2)
                {
                    if (this.getBlock(k1, l1, i2).getMaterial() == p_72875_2_)
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 检查给定的 AxisAlignedBB 范围是否与指定的物质相交。通常用于游泳时。
     *
     * @param p_72830_1_ 需要检查的 AxisAlignedBB 范围。
     * @param p_72830_2_ 需要检查的物质类型。
     * @return 如果 AxisAlignedBB 范围与指定的物质相交，返回 true；否则返回 false。
     */
    public boolean isAABBInMaterial(AxisAlignedBB p_72830_1_, Material p_72830_2_)
    {
        int i = MathHelper.floor_double(p_72830_1_.minX);
        int j = MathHelper.floor_double(p_72830_1_.maxX + 1.0D);
        int k = MathHelper.floor_double(p_72830_1_.minY);
        int l = MathHelper.floor_double(p_72830_1_.maxY + 1.0D);
        int i1 = MathHelper.floor_double(p_72830_1_.minZ);
        int j1 = MathHelper.floor_double(p_72830_1_.maxZ + 1.0D);

        for (int k1 = i; k1 < j; ++k1)
        {
            for (int l1 = k; l1 < l; ++l1)
            {
                for (int i2 = i1; i2 < j1; ++i2)
                {
                    Block block = this.getBlock(k1, l1, i2);

                    if (block.getMaterial() == p_72830_2_)
                    {
                        int j2 = this.getBlockMetadata(k1, l1, i2);
                        double d0 = (double)(l1 + 1);

                        if (j2 < 8)
                        {
                            d0 = (double)(l1 + 1) - (double)j2 / 8.0D;
                        }

                        if (d0 >= p_72830_1_.minY)
                        {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * 创建一个爆炸事件。
     *
     * @param p_72876_1_ 触发爆炸的实体（可以为 null）。
     * @param p_72876_2_ 爆炸中心的 X 坐标。
     * @param p_72876_4_ 爆炸中心的 Y 坐标。
     * @param p_72876_6_ 爆炸中心的 Z 坐标。
     * @param p_72876_8_ 爆炸的强度（大小）。
     * @param p_72876_9_ 是否引发火焰。
     * @return 返回创建的 Explosion 对象。
     */
    public Explosion createExplosion(Entity p_72876_1_, double p_72876_2_, double p_72876_4_, double p_72876_6_, float p_72876_8_, boolean p_72876_9_)
    {
        return this.newExplosion(p_72876_1_, p_72876_2_, p_72876_4_, p_72876_6_, p_72876_8_, false, p_72876_9_);
    }

    /**
     * 创建一个新的爆炸事件，并执行相关的初始化操作。
     *
     * @param p_72885_1_ 触发爆炸的实体（可以为 null）。
     * @param p_72885_2_ 爆炸中心的 X 坐标。
     * @param p_72885_4_ 爆炸中心的 Y 坐标。
     * @param p_72885_6_ 爆炸中心的 Z 坐标。
     * @param p_72885_8_ 爆炸的强度（大小）。
     * @param p_72885_9_ 是否引发火焰。
     * @param p_72885_10_ 是否产生烟雾。
     * @return 返回创建的 Explosion 对象。
     */
    public Explosion newExplosion(Entity p_72885_1_, double p_72885_2_, double p_72885_4_, double p_72885_6_, float p_72885_8_, boolean p_72885_9_, boolean p_72885_10_)
    {
        Explosion explosion = new Explosion(this, p_72885_1_, p_72885_2_, p_72885_4_, p_72885_6_, p_72885_8_);
        explosion.isFlaming = p_72885_9_;
        explosion.isSmoking = p_72885_10_;
        if (net.minecraftforge.event.ForgeEventFactory.onExplosionStart(this, explosion)) return explosion;
        explosion.doExplosionA();
        explosion.doExplosionB(true);
        return explosion;
    }

    /**
     * 计算在指定的 AxisAlignedBB 范围内，沿指定的向量方向上的真实方块的百分比。
     *
     * @param p_72842_1_ 方向向量。
     * @param p_72842_2_ 需要计算的 AxisAlignedBB 范围。
     * @return 返回真实方块的百分比。
     */
    public float getBlockDensity(Vec3 p_72842_1_, AxisAlignedBB p_72842_2_)
    {
        double d0 = 1.0D / ((p_72842_2_.maxX - p_72842_2_.minX) * 2.0D + 1.0D);
        double d1 = 1.0D / ((p_72842_2_.maxY - p_72842_2_.minY) * 2.0D + 1.0D);
        double d2 = 1.0D / ((p_72842_2_.maxZ - p_72842_2_.minZ) * 2.0D + 1.0D);

        if (d0 >= 0.0D && d1 >= 0.0D && d2 >= 0.0D)
        {
            int i = 0;
            int j = 0;

            for (float f = 0.0F; f <= 1.0F; f = (float)((double)f + d0))
            {
                for (float f1 = 0.0F; f1 <= 1.0F; f1 = (float)((double)f1 + d1))
                {
                    for (float f2 = 0.0F; f2 <= 1.0F; f2 = (float)((double)f2 + d2))
                    {
                        double d3 = p_72842_2_.minX + (p_72842_2_.maxX - p_72842_2_.minX) * (double)f;
                        double d4 = p_72842_2_.minY + (p_72842_2_.maxY - p_72842_2_.minY) * (double)f1;
                        double d5 = p_72842_2_.minZ + (p_72842_2_.maxZ - p_72842_2_.minZ) * (double)f2;

                        if (this.rayTraceBlocks(Vec3.createVectorHelper(d3, d4, d5), p_72842_1_) == null)
                        {
                            ++i;
                        }

                        ++j;
                    }
                }
            }

            return (float)i / (float)j;
        }
        else
        {
            return 0.0F;
        }
    }

    /**
     * 如果指定坐标的指定方向的方块是火，则扑灭它。
     *
     * @param player 触发扑灭操作的玩家。
     * @param x 方块的 X 坐标。
     * @param y 方块的 Y 坐标。
     * @param z 方块的 Z 坐标。
     * @param side 方块的方向（0: 下, 1: 上, 2: -Z, 3: +Z, 4: -X, 5: +X）。
     * @return 如果成功扑灭了火，返回 true；否则返回 false。
     */
    public boolean extinguishFire(EntityPlayer player, int x, int y, int z, int side)
    {
        if (side == 0)
        {
            --y;
        }

        if (side == 1)
        {
            ++y;
        }

        if (side == 2)
        {
            --z;
        }

        if (side == 3)
        {
            ++z;
        }

        if (side == 4)
        {
            --x;
        }

        if (side == 5)
        {
            ++x;
        }

        if (this.getBlock(x, y, z) == Blocks.fire)
        {
            this.playAuxSFXAtEntity(player, 1004, x, y, z, 0);
            this.setBlockToAir(x, y, z);
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * 获取当前加载的实体数量的调试信息字符串。
     * 该信息可通过按 F3 键查看。
     *
     * @return 返回当前加载的实体数量的字符串表示，例如 "All: 123"。
     */
    @SideOnly(Side.CLIENT)
    public String getDebugLoadedEntities()
    {
        return "All: " + this.loadedEntityList.size();
    }

    /**
     * 返回当前区块提供程序的名称。
     * 通过调用 chunkprovider.makeString() 获取名称。
     *
     * @return 返回当前区块提供程序的名称。
     */
    @SideOnly(Side.CLIENT)
    public String getProviderName()
    {
        return this.chunkProvider.makeString();
    }
    /**
     * 获取指定坐标处的 TileEntity 实例。
     *
     * @param x X 坐标。
     * @param y Y 坐标。
     * @param z Z 坐标。
     * @return 返回指定坐标处的 TileEntity 实例，如果不存在则返回 null。
     */
    public TileEntity getTileEntity(int x, int y, int z)
    {
        if (y >= 0 && y < 256)
        {
            TileEntity tileentity = null;
            int l;
            TileEntity tileentity1;

            if (this.field_147481_N)
            {
                for (l = 0; l < this.addedTileEntityList.size(); ++l)
                {
                    tileentity1 = (TileEntity)this.addedTileEntityList.get(l);

                    if (!tileentity1.isInvalid() && tileentity1.xCoord == x && tileentity1.yCoord == y && tileentity1.zCoord == z)
                    {
                        tileentity = tileentity1;
                        break;
                    }
                }
            }

            if (tileentity == null)
            {
                Chunk chunk = this.getChunkFromChunkCoords(x >> 4, z >> 4);

                if (chunk != null)
                {
                    tileentity = chunk.func_150806_e(x & 15, y, z & 15);
                }
            }

            if (tileentity == null)
            {
                for (l = 0; l < this.addedTileEntityList.size(); ++l)
                {
                    tileentity1 = (TileEntity)this.addedTileEntityList.get(l);

                    if (!tileentity1.isInvalid() && tileentity1.xCoord == x && tileentity1.yCoord == y && tileentity1.zCoord == z)
                    {
                        tileentity = tileentity1;
                        break;
                    }
                }
            }

            return tileentity;
        }
        else
        {
            return null;
        }
    }
    /**
     * 设置指定坐标处的 TileEntity 实例。
     * 如果 TileEntity 实例为 null 或无效，则不执行任何操作。
     *
     * @param x X 坐标。
     * @param y Y 坐标。
     * @param z Z 坐标。
     * @param tileEntityIn 要设置的 TileEntity 实例。
     */
    public void setTileEntity(int x, int y, int z, TileEntity tileEntityIn)
    {
        if (tileEntityIn == null || tileEntityIn.isInvalid())
        {
            return;
        }

        if (tileEntityIn.canUpdate())
        {
            if (this.field_147481_N)
            {
                Iterator iterator = this.addedTileEntityList.iterator();

                while (iterator.hasNext())
                {
                    TileEntity tileentity1 = (TileEntity)iterator.next();

                    if (tileentity1.xCoord == x && tileentity1.yCoord == y && tileentity1.zCoord == z)
                    {
                        tileentity1.invalidate();
                        iterator.remove();
                    }
                }

                this.addedTileEntityList.add(tileEntityIn);
            }
            else
            {
                this.loadedTileEntityList.add(tileEntityIn);
            }
        }
        Chunk chunk = this.getChunkFromChunkCoords(x >> 4, z >> 4);
        if (chunk != null)
        {
            chunk.func_150812_a(x & 15, y, z & 15, tileEntityIn);
        }
        //notify tile changes
        func_147453_f(x, y, z, getBlock(x, y, z));
    }
    /**
     * 移除指定坐标处的 TileEntity 实例。
     *
     * @param x X 坐标。
     * @param y Y 坐标。
     * @param z Z 坐标。
     */
    public void removeTileEntity(int x, int y, int z)
    {
        Chunk chunk = getChunkFromChunkCoords(x >> 4, z >> 4);
        if (chunk != null) chunk.removeTileEntity(x & 15, y, z & 15);
        func_147453_f(x, y, z, getBlock(x, y, z));
    }
    /**
     * 将指定的 TileEntity 实例添加到待处理列表中。
     *
     * @param tileEntityIn 要添加的 TileEntity 实例。
     */
    public void func_147457_a(TileEntity tileEntityIn)
    {
        this.field_147483_b.add(tileEntityIn);
    }
    /**
     * 检查指定坐标处的方块是否具有碰撞边界框，并且该边界框的平均边长是否大于或等于 1.0D。
     *
     * @param x X 坐标。
     * @param y Y 坐标。
     * @param z Z 坐标。
     * @return 如果方块具有有效的碰撞边界框且边界框的平均边长大于或等于 1.0D，则返回 true；否则返回 false。
     */
    public boolean func_147469_q(int x, int y, int z)
    {
        AxisAlignedBB axisalignedbb = this.getBlock(x, y, z).getCollisionBoundingBoxFromPool(this, x, y, z);
        return axisalignedbb != null && axisalignedbb.getAverageEdgeLength() >= 1.0D;
    }

    /**
     * 返回指定坐标处的方块是否具有一个实心（可构建）的顶部表面。
     *
     * @param worldIn 世界实例。
     * @param x X 坐标。
     * @param y Y 坐标。
     * @param z Z 坐标。
     * @return 如果方块的顶部表面是实心的，则返回 true；否则返回 false。
     */
    public static boolean doesBlockHaveSolidTopSurface(IBlockAccess worldIn, int x, int y, int z)
    {
        Block block = worldIn.getBlock(x, y, z);
        return block.isSideSolid(worldIn, x, y, z, ForgeDirection.UP);
    }

    /**
     * 检查指定坐标处的方块是否是实心的正常立方体。
     * 如果区块不存在或未加载，则返回指定的默认布尔值。
     *
     * @param x X 坐标。
     * @param y Y 坐标。
     * @param z Z 坐标。
     * @param def 区块不存在或未加载时返回的默认布尔值。
     * @return 如果方块是实心的正常立方体，则返回 true；否则返回 false。
     */
    public boolean isBlockNormalCubeDefault(int x, int y, int z, boolean def)
    {
        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000)
        {
            Chunk chunk = this.chunkProvider.provideChunk(x >> 4, z >> 4);

            if (chunk != null && !chunk.isEmpty())
            {
                Block block = this.getBlock(x, y, z);
                return block.isNormalCube(this, x, y, z);
            }
            else
            {
                return def;
            }
        }
        else
        {
            return def;
        }
    }

    /**
     * 在 World 类的构造函数中调用，以设置初始的天空光照值。
     */
    public void calculateInitialSkylight()
    {
        int i = this.calculateSkylightSubtracted(1.0F);

        if (i != this.skylightSubtracted)
        {
            this.skylightSubtracted = i;
        }
    }

    /**
     * 设置允许的生成类型（敌对生物与友好生物）。
     *
     * @param hostile 是否允许敌对生物生成。
     * @param peaceful 是否允许友好生物生成。
     */
    public void setAllowedSpawnTypes(boolean hostile, boolean peaceful)
    {
        provider.setAllowedSpawnTypes(hostile, peaceful);
    }

    /**
     * 执行世界的单个刻（tick）。
     */
    public void tick()
    {
        this.updateWeather();
    }

    /**
     * 从 World 构造函数调用，以设置 rainingStrength 和 thunderingStrength。
     */
    private void calculateInitialWeather()
    {
        provider.calculateInitialWeather();
    }
    /**
     * 根据世界信息设置初始天气状态（是否下雨、是否打雷）。
     */
    public void calculateInitialWeatherBody()
    {
        if (this.worldInfo.isRaining())
        {
            this.rainingStrength = 1.0F;

            if (this.worldInfo.isThundering())
            {
                this.thunderingStrength = 1.0F;
            }
        }
    }

    /**
     * 更新所有天气状态。
     */
    protected void updateWeather()
    {
        provider.updateWeather();
    }
    /**
     * 更新世界的天气状态，包括雷电和降雨的强度和持续时间。
     */
    public void updateWeatherBody()
    {
        if (!this.provider.hasNoSky)
        {
            if (!this.isRemote)
            {
                int i = this.worldInfo.getThunderTime();

                if (i <= 0)
                {
                    if (this.worldInfo.isThundering())
                    {
                        this.worldInfo.setThunderTime(this.rand.nextInt(12000) + 3600);
                    }
                    else
                    {
                        this.worldInfo.setThunderTime(this.rand.nextInt(168000) + 12000);
                    }
                }
                else
                {
                    --i;
                    this.worldInfo.setThunderTime(i);

                    if (i <= 0)
                    {
                        this.worldInfo.setThundering(!this.worldInfo.isThundering());
                    }
                }

                this.prevThunderingStrength = this.thunderingStrength;

                if (this.worldInfo.isThundering())
                {
                    this.thunderingStrength = (float)((double)this.thunderingStrength + 0.01D);
                }
                else
                {
                    this.thunderingStrength = (float)((double)this.thunderingStrength - 0.01D);
                }

                this.thunderingStrength = MathHelper.clamp_float(this.thunderingStrength, 0.0F, 1.0F);
                int j = this.worldInfo.getRainTime();

                if (j <= 0)
                {
                    if (this.worldInfo.isRaining())
                    {
                        this.worldInfo.setRainTime(this.rand.nextInt(12000) + 12000);
                    }
                    else
                    {
                        this.worldInfo.setRainTime(this.rand.nextInt(168000) + 12000);
                    }
                }
                else
                {
                    --j;
                    this.worldInfo.setRainTime(j);

                    if (j <= 0)
                    {
                        this.worldInfo.setRaining(!this.worldInfo.isRaining());
                    }
                }

                this.prevRainingStrength = this.rainingStrength;

                if (this.worldInfo.isRaining())
                {
                    this.rainingStrength = (float)((double)this.rainingStrength + 0.01D);
                }
                else
                {
                    this.rainingStrength = (float)((double)this.rainingStrength - 0.01D);
                }

                this.rainingStrength = MathHelper.clamp_float(this.rainingStrength, 0.0F, 1.0F);
            }
        }
    }
    /**
     * 更新当前激活的玩家区块，并检查光照。
     */
    protected void setActivePlayerChunksAndCheckLight()
    {
        this.activeChunkSet.clear();
        this.theProfiler.startSection("buildList");
        this.activeChunkSet.addAll(getPersistentChunks().keySet());
        int i;
        EntityPlayer entityplayer;
        int j;
        int k;
        int l;

        for (i = 0; i < this.playerEntities.size(); ++i)
        {
            entityplayer = (EntityPlayer)this.playerEntities.get(i);
            j = MathHelper.floor_double(entityplayer.posX / 16.0D);
            k = MathHelper.floor_double(entityplayer.posZ / 16.0D);
            l = this.func_152379_p();

            for (int i1 = -l; i1 <= l; ++i1)
            {
                for (int j1 = -l; j1 <= l; ++j1)
                {
                    this.activeChunkSet.add(new ChunkCoordIntPair(i1 + j, j1 + k));
                }
            }
        }

        this.theProfiler.endSection();

        if (this.ambientTickCountdown > 0)
        {
            --this.ambientTickCountdown;
        }

        this.theProfiler.startSection("playerCheckLight");

        if (!this.playerEntities.isEmpty())
        {
            i = this.rand.nextInt(this.playerEntities.size());
            entityplayer = (EntityPlayer)this.playerEntities.get(i);
            j = MathHelper.floor_double(entityplayer.posX) + this.rand.nextInt(11) - 5;
            k = MathHelper.floor_double(entityplayer.posY) + this.rand.nextInt(11) - 5;
            l = MathHelper.floor_double(entityplayer.posZ) + this.rand.nextInt(11) - 5;
            this.func_147451_t(j, k, l);
        }

        this.theProfiler.endSection();
    }

    protected abstract int func_152379_p();
    /**
     * 处理给定区块的环境音效和光照检查。
     *
     * @param p_147467_1_ 区块的 X 坐标偏移量
     * @param p_147467_2_ 区块的 Z 坐标偏移量
     * @param p_147467_3_ 需要处理的区块
     */
    protected void func_147467_a(int p_147467_1_, int p_147467_2_, Chunk p_147467_3_)
    {
        this.theProfiler.endStartSection("moodSound");

        if (this.ambientTickCountdown == 0 && !this.isRemote)
        {
            this.updateLCG = this.updateLCG * 3 + 1013904223;
            int k = this.updateLCG >> 2;
            int l = k & 15;
            int i1 = k >> 8 & 15;
            int j1 = k >> 16 & 255;
            Block block = p_147467_3_.getBlock(l, j1, i1);
            l += p_147467_1_;
            i1 += p_147467_2_;

            if (block.getMaterial() == Material.air && this.getFullBlockLightValue(l, j1, i1) <= this.rand.nextInt(8) && this.getSavedLightValue(EnumSkyBlock.Sky, l, j1, i1) <= 0)
            {
                EntityPlayer entityplayer = this.getClosestPlayer((double)l + 0.5D, (double)j1 + 0.5D, (double)i1 + 0.5D, 8.0D);

                if (entityplayer != null && entityplayer.getDistanceSq((double)l + 0.5D, (double)j1 + 0.5D, (double)i1 + 0.5D) > 4.0D)
                {
                    this.playSoundEffect((double)l + 0.5D, (double)j1 + 0.5D, (double)i1 + 0.5D, "ambient.cave.cave", 0.7F, 0.8F + this.rand.nextFloat() * 0.2F);
                    this.ambientTickCountdown = this.rand.nextInt(12000) + 6000;
                }
            }
        }

        this.theProfiler.endStartSection("checkLight");
        p_147467_3_.enqueueRelightChecks();
    }
    /**
     * 更新活动玩家的区块，并检查光照。
     */
    protected void func_147456_g()
    {
        this.setActivePlayerChunksAndCheckLight();
    }

    /**
     * 检查指定坐标的方块是否为水，并且足够冷以至于能冻结。
     *
     * @param x 方块的 X 坐标
     * @param y 方块的 Y 坐标
     * @param z 方块的 Z 坐标
     * @return 如果方块可以冻结，则返回 true，否则返回 false
     */
    public boolean isBlockFreezable(int x, int y, int z)
    {
        return this.canBlockFreeze(x, y, z, false);
    }

    /**
     * 检查指定坐标的方块是否为水，并且有至少一个相邻的非水方块。
     *
     * @param x 方块的 X 坐标
     * @param y 方块的 Y 坐标
     * @param z 方块的 Z 坐标
     * @return 如果方块可以自然冻结，则返回 true，否则返回 false
     */
    public boolean isBlockFreezableNaturally(int x, int y, int z)
    {
        return this.canBlockFreeze(x, y, z, true);
    }

    /**
     * 检查指定坐标的方块是否为水，并且冷得足以冻结。如果 byWater 为 true，只有在相邻的方块中至少有一个是非水方块时才返回 true。
     *
     * @param x 方块的 X 坐标
     * @param y 方块的 Y 坐标
     * @param z 方块的 Z 坐标
     * @param byWater 是否检查相邻方块是否为水
     * @return 如果方块可以冻结，则返回 true，否则返回 false
     */
    public boolean canBlockFreeze(int x, int y, int z, boolean byWater)
    {
        return provider.canBlockFreeze(x, y, z, byWater);
    }
    /**
     * 检查指定坐标的方块是否为水，并且足够冷以至于能冻结。如果 byWater 为 true，只有在相邻的方块中至少有一个是非水方块时才返回 true。
     *
     * @param x 方块的 X 坐标
     * @param y 方块的 Y 坐标
     * @param z 方块的 Z 坐标
     * @param byWater 是否检查相邻方块是否为水
     * @return 如果方块可以冻结，则返回 true，否则返回 false
     */
    public boolean canBlockFreezeBody(int x, int y, int z, boolean byWater)
    {
        BiomeGenBase biomegenbase = this.getBiomeGenForCoords(x, z);
        float f = biomegenbase.getFloatTemperature(x, y, z);

        if (f > 0.15F)
        {
            return false;
        }
        else
        {
            if (y >= 0 && y < 256 && this.getSavedLightValue(EnumSkyBlock.Block, x, y, z) < 10)
            {
                Block block = this.getBlock(x, y, z);

                if ((block == Blocks.water || block == Blocks.flowing_water) && this.getBlockMetadata(x, y, z) == 0)
                {
                    if (!byWater)
                    {
                        return true;
                    }

                    boolean flag1 = true;

                    if (flag1 && this.getBlock(x - 1, y, z).getMaterial() != Material.water)
                    {
                        flag1 = false;
                    }

                    if (flag1 && this.getBlock(x + 1, y, z).getMaterial() != Material.water)
                    {
                        flag1 = false;
                    }

                    if (flag1 && this.getBlock(x, y, z - 1).getMaterial() != Material.water)
                    {
                        flag1 = false;
                    }

                    if (flag1 && this.getBlock(x, y, z + 1).getMaterial() != Material.water)
                    {
                        flag1 = false;
                    }

                    if (!flag1)
                    {
                        return true;
                    }
                }
            }

            return false;
        }
    }
    /**
     * 检查指定坐标的方块是否可以下雪。
     *
     * @param x 方块的 X 坐标
     * @param y 方块的 Y 坐标
     * @param z 方块的 Z 坐标
     * @param checkLight 是否检查光照
     * @return 如果方块可以下雪，则返回 true，否则返回 false
     */
    public boolean func_147478_e(int x, int y, int z, boolean checkLight)
    {
        return provider.canSnowAt(x, y, z, checkLight);
    }
    /**
     * 检查指定坐标的方块是否可以下雪。考虑生物群落的温度和光照。
     *
     * @param x 方块的 X 坐标
     * @param y 方块的 Y 坐标
     * @param z 方块的 Z 坐标
     * @param checkLight 是否检查光照
     * @return 如果方块可以下雪，则返回 true，否则返回 false
     */
    public boolean canSnowAtBody(int x, int y, int z, boolean checkLight)
    {
        BiomeGenBase biomegenbase = this.getBiomeGenForCoords(x, z);
        float f = biomegenbase.getFloatTemperature(x, y, z);

        if (f > 0.15F)
        {
            return false;
        }
        else if (!checkLight)
        {
            return true;
        }
        else
        {
            if (y >= 0 && y < 256 && this.getSavedLightValue(EnumSkyBlock.Block, x, y, z) < 10)
            {
                Block block = this.getBlock(x, y, z);

                if (block.getMaterial() == Material.air && Blocks.snow_layer.canPlaceBlockAt(this, x, y, z))
                {
                    return true;
                }
            }

            return false;
        }
    }
    public boolean func_147451_t(int x, int y, int z)
    {
        boolean flag = false;

        if (!this.provider.hasNoSky)
        {
            flag |= this.updateLightByType(EnumSkyBlock.Sky, x, y, z);
        }

        flag |= this.updateLightByType(EnumSkyBlock.Block, x, y, z);
        return flag;
    }
    /**
     * 计算指定坐标的光照值。
     *
     * @param x 方块的 X 坐标
     * @param y 方块的 Y 坐标
     * @param z 方块的 Z 坐标
     * @param p_98179_4_ 光照类型（天空光或方块光）
     * @return 计算得到的光照值
     */
    private int computeLightValue(int x, int y, int z, EnumSkyBlock p_98179_4_)
    {
        if (p_98179_4_ == EnumSkyBlock.Sky && this.canBlockSeeTheSky(x, y, z))
        {
            return 15;
        }
        else
        {
            Block block = this.getBlock(x, y, z);
            int blockLight = block.getLightValue(this, x, y, z);
            int l = p_98179_4_ == EnumSkyBlock.Sky ? 0 : blockLight;
            int i1 = block.getLightOpacity(this, x, y, z);

            if (i1 >= 15 && blockLight > 0)
            {
                i1 = 1;
            }

            if (i1 < 1)
            {
                i1 = 1;
            }

            if (i1 >= 15)
            {
                return 0;
            }
            else if (l >= 14)
            {
                return l;
            }
            else
            {
                for (int j1 = 0; j1 < 6; ++j1)
                {
                    int k1 = x + Facing.offsetsXForSide[j1];
                    int l1 = y + Facing.offsetsYForSide[j1];
                    int i2 = z + Facing.offsetsZForSide[j1];
                    int j2 = this.getSavedLightValue(p_98179_4_, k1, l1, i2) - i1;

                    if (j2 > l)
                    {
                        l = j2;
                    }

                    if (l >= 14)
                    {
                        return l;
                    }
                }

                return l;
            }
        }
    }
    /**
     * 根据指定的光照类型更新光照值。
     *
     * @param p_147463_1_ 光照类型（天空光或方块光）
     * @param p_147463_2_ 方块的 X 坐标
     * @param p_147463_3_ 方块的 Y 坐标
     * @param p_147463_4_ 方块的 Z 坐标
     * @return 如果光照值被更新，则返回 true，否则返回 false
     */
    public boolean updateLightByType(EnumSkyBlock p_147463_1_, int p_147463_2_, int p_147463_3_, int p_147463_4_)
    {
        if (!this.doChunksNearChunkExist(p_147463_2_, p_147463_3_, p_147463_4_, 17))
        {
            return false;
        }
        else
        {
            int l = 0;
            int i1 = 0;
            this.theProfiler.startSection("getBrightness");
            int j1 = this.getSavedLightValue(p_147463_1_, p_147463_2_, p_147463_3_, p_147463_4_);
            int k1 = this.computeLightValue(p_147463_2_, p_147463_3_, p_147463_4_, p_147463_1_);
            int l1;
            int i2;
            int j2;
            int k2;
            int l2;
            int i3;
            int j3;
            int k3;
            int l3;

            if (k1 > j1)
            {
                this.lightUpdateBlockList[i1++] = 133152;
            }
            else if (k1 < j1)
            {
                this.lightUpdateBlockList[i1++] = 133152 | j1 << 18;

                while (l < i1)
                {
                    l1 = this.lightUpdateBlockList[l++];
                    i2 = (l1 & 63) - 32 + p_147463_2_;
                    j2 = (l1 >> 6 & 63) - 32 + p_147463_3_;
                    k2 = (l1 >> 12 & 63) - 32 + p_147463_4_;
                    l2 = l1 >> 18 & 15;
                    i3 = this.getSavedLightValue(p_147463_1_, i2, j2, k2);

                    if (i3 == l2)
                    {
                        this.setLightValue(p_147463_1_, i2, j2, k2, 0);

                        if (l2 > 0)
                        {
                            j3 = MathHelper.abs_int(i2 - p_147463_2_);
                            k3 = MathHelper.abs_int(j2 - p_147463_3_);
                            l3 = MathHelper.abs_int(k2 - p_147463_4_);

                            if (j3 + k3 + l3 < 17)
                            {
                                for (int i4 = 0; i4 < 6; ++i4)
                                {
                                    int j4 = i2 + Facing.offsetsXForSide[i4];
                                    int k4 = j2 + Facing.offsetsYForSide[i4];
                                    int l4 = k2 + Facing.offsetsZForSide[i4];
                                    int i5 = Math.max(1, this.getBlock(j4, k4, l4).getLightOpacity(this, j4, k4, l4));
                                    i3 = this.getSavedLightValue(p_147463_1_, j4, k4, l4);

                                    if (i3 == l2 - i5 && i1 < this.lightUpdateBlockList.length)
                                    {
                                        this.lightUpdateBlockList[i1++] = j4 - p_147463_2_ + 32 | k4 - p_147463_3_ + 32 << 6 | l4 - p_147463_4_ + 32 << 12 | l2 - i5 << 18;
                                    }
                                }
                            }
                        }
                    }
                }

                l = 0;
            }

            this.theProfiler.endSection();
            this.theProfiler.startSection("checkedPosition < toCheckCount");

            while (l < i1)
            {
                l1 = this.lightUpdateBlockList[l++];
                i2 = (l1 & 63) - 32 + p_147463_2_;
                j2 = (l1 >> 6 & 63) - 32 + p_147463_3_;
                k2 = (l1 >> 12 & 63) - 32 + p_147463_4_;
                l2 = this.getSavedLightValue(p_147463_1_, i2, j2, k2);
                i3 = this.computeLightValue(i2, j2, k2, p_147463_1_);

                if (i3 != l2)
                {
                    this.setLightValue(p_147463_1_, i2, j2, k2, i3);

                    if (i3 > l2)
                    {
                        j3 = Math.abs(i2 - p_147463_2_);
                        k3 = Math.abs(j2 - p_147463_3_);
                        l3 = Math.abs(k2 - p_147463_4_);
                        boolean flag = i1 < this.lightUpdateBlockList.length - 6;

                        if (j3 + k3 + l3 < 17 && flag)
                        {
                            if (this.getSavedLightValue(p_147463_1_, i2 - 1, j2, k2) < i3)
                            {
                                this.lightUpdateBlockList[i1++] = i2 - 1 - p_147463_2_ + 32 + (j2 - p_147463_3_ + 32 << 6) + (k2 - p_147463_4_ + 32 << 12);
                            }

                            if (this.getSavedLightValue(p_147463_1_, i2 + 1, j2, k2) < i3)
                            {
                                this.lightUpdateBlockList[i1++] = i2 + 1 - p_147463_2_ + 32 + (j2 - p_147463_3_ + 32 << 6) + (k2 - p_147463_4_ + 32 << 12);
                            }

                            if (this.getSavedLightValue(p_147463_1_, i2, j2 - 1, k2) < i3)
                            {
                                this.lightUpdateBlockList[i1++] = i2 - p_147463_2_ + 32 + (j2 - 1 - p_147463_3_ + 32 << 6) + (k2 - p_147463_4_ + 32 << 12);
                            }

                            if (this.getSavedLightValue(p_147463_1_, i2, j2 + 1, k2) < i3)
                            {
                                this.lightUpdateBlockList[i1++] = i2 - p_147463_2_ + 32 + (j2 + 1 - p_147463_3_ + 32 << 6) + (k2 - p_147463_4_ + 32 << 12);
                            }

                            if (this.getSavedLightValue(p_147463_1_, i2, j2, k2 - 1) < i3)
                            {
                                this.lightUpdateBlockList[i1++] = i2 - p_147463_2_ + 32 + (j2 - p_147463_3_ + 32 << 6) + (k2 - 1 - p_147463_4_ + 32 << 12);
                            }

                            if (this.getSavedLightValue(p_147463_1_, i2, j2, k2 + 1) < i3)
                            {
                                this.lightUpdateBlockList[i1++] = i2 - p_147463_2_ + 32 + (j2 - p_147463_3_ + 32 << 6) + (k2 + 1 - p_147463_4_ + 32 << 12);
                            }
                        }
                    }
                }
            }

            this.theProfiler.endSection();
            return true;
        }
    }

    /**
     * 处理待更新的方块列表，并执行更新操作。
     *
     * @param p_72955_1_ 是否进行完整的更新
     * @return 总是返回 false
     */
    public boolean tickUpdates(boolean p_72955_1_)
    {
        return false;
    }
    /**
     * 获取指定区块的待处理方块更新列表。
     *
     * @param p_72920_1_ 目标区块
     * @param p_72920_2_ 是否需要检查所有更新
     * @return 当前实现返回 null
     */
    public List<net.minecraft.world.NextTickListEntry> getPendingBlockUpdates(Chunk p_72920_1_, boolean p_72920_2_)
    {
        return null;
    }

    /**
     * 获取指定包围盒（AABB）内的所有实体，排除指定的实体。
     *
     * @param p_72839_1_ 要排除的实体
     * @param p_72839_2_ 要检查的包围盒
     * @return 包含所有符合条件的实体的列表
     */
    public List<net.minecraft.entity.Entity> getEntitiesWithinAABBExcludingEntity(Entity p_72839_1_, AxisAlignedBB p_72839_2_)
    {
        return this.getEntitiesWithinAABBExcludingEntity(p_72839_1_, p_72839_2_, (IEntitySelector)null);
    }
    /**
     * 获取指定包围盒（AABB）内的所有实体，排除指定的实体，并根据提供的选择器过滤实体。
     *
     * @param p_94576_1_ 要排除的实体
     * @param p_94576_2_ 要检查的包围盒
     * @param p_94576_3_ 实体选择器
     * @return 包含所有符合条件的实体的列表
     */
    public List<net.minecraft.entity.Entity> getEntitiesWithinAABBExcludingEntity(Entity p_94576_1_, AxisAlignedBB p_94576_2_, IEntitySelector p_94576_3_)
    {
        ArrayList arraylist = new ArrayList();
        int i = MathHelper.floor_double((p_94576_2_.minX - MAX_ENTITY_RADIUS) / 16.0D);
        int j = MathHelper.floor_double((p_94576_2_.maxX + MAX_ENTITY_RADIUS) / 16.0D);
        int k = MathHelper.floor_double((p_94576_2_.minZ - MAX_ENTITY_RADIUS) / 16.0D);
        int l = MathHelper.floor_double((p_94576_2_.maxZ + MAX_ENTITY_RADIUS) / 16.0D);

        for (int i1 = i; i1 <= j; ++i1)
        {
            for (int j1 = k; j1 <= l; ++j1)
            {
                if (this.chunkExists(i1, j1))
                {
                    this.getChunkFromChunkCoords(i1, j1).getEntitiesWithinAABBForEntity(p_94576_1_, p_94576_2_, arraylist, p_94576_3_);
                }
            }
        }

        return arraylist;
    }

    /**
     * 返回与指定包围盒（AABB）相交的所有指定类型的实体。
     *
     * @param <T> 实体类型
     * @param p_72872_1_ 实体类
     * @param p_72872_2_ 包围盒
     * @return 包含所有符合条件的实体的列表
     */
    public <T> List<T> getEntitiesWithinAABB(Class<T> p_72872_1_, AxisAlignedBB p_72872_2_)
    {
        return this.selectEntitiesWithinAABB(p_72872_1_, p_72872_2_, (IEntitySelector)null);
    }
    /**
     * 根据提供的选择器返回与指定包围盒（AABB）相交的所有指定类型的实体。
     *
     * @param <T> 实体类型
     * @param clazz 实体类
     * @param bb 包围盒
     * @param selector 实体选择器
     * @return 包含所有符合条件的实体的列表
     */
    public <T> List<T> selectEntitiesWithinAABB(Class<T> clazz, AxisAlignedBB bb, IEntitySelector selector)
    {
        int i = MathHelper.floor_double((bb.minX - MAX_ENTITY_RADIUS) / 16.0D);
        int j = MathHelper.floor_double((bb.maxX + MAX_ENTITY_RADIUS) / 16.0D);
        int k = MathHelper.floor_double((bb.minZ - MAX_ENTITY_RADIUS) / 16.0D);
        int l = MathHelper.floor_double((bb.maxZ + MAX_ENTITY_RADIUS) / 16.0D);
        ArrayList arraylist = new ArrayList();

        for (int i1 = i; i1 <= j; ++i1)
        {
            for (int j1 = k; j1 <= l; ++j1)
            {
                if (this.chunkExists(i1, j1))
                {
                    this.getChunkFromChunkCoords(i1, j1).getEntitiesOfTypeWithinAAAB(clazz, bb, arraylist, selector);
                }
            }
        }

        return arraylist;
    }
    /**
     * 在指定包围盒（AABB）内找到与指定实体最接近的实体。
     *
     * @param p_72857_1_ 实体类
     * @param p_72857_2_ 包围盒
     * @param p_72857_3_ 要计算距离的实体
     * @return 距离指定实体最近的实体
     */
    public Entity findNearestEntityWithinAABB(Class<? extends net.minecraft.entity.Entity> p_72857_1_, AxisAlignedBB p_72857_2_, Entity p_72857_3_)
    {
        List list = this.getEntitiesWithinAABB(p_72857_1_, p_72857_2_);
        Entity entity1 = null;
        double d0 = Double.MAX_VALUE;

        for (int i = 0; i < list.size(); ++i)
        {
            Entity entity2 = (Entity)list.get(i);

            if (entity2 != p_72857_3_)
            {
                double d1 = p_72857_3_.getDistanceSqToEntity(entity2);

                if (d1 <= d0)
                {
                    entity1 = entity2;
                    d0 = d1;
                }
            }
        }

        return entity1;
    }

    /**
     * 返回具有指定 ID 的实体，如果该实体在此世界中不存在，则返回 null。
     *
     * @param p_73045_1_ 实体 ID
     * @return 对应 ID 的实体或 null
     */
    public abstract Entity getEntityByID(int p_73045_1_);
    /**
     * 获取当前已加载的实体列表。
     *
     * @return 当前已加载的实体列表
     */
    @SideOnly(Side.CLIENT)
    public List<net.minecraft.entity.Entity> getLoadedEntityList()
    {
        return this.loadedEntityList;
    }

    /**
     * 标记指定位置的区块为已修改。对 TileEntity 进行操作时，需要标记区块为修改状态，以避免游戏退出时区块回滚。
     *
     * @param p_147476_1_ 方块的 X 坐标
     * @param p_147476_2_ 方块的 Y 坐标
     * @param p_147476_3_ 方块的 Z 坐标
     * @param p_147476_4_ 需要标记的 TileEntity
     */
    public void markTileEntityChunkModified(int p_147476_1_, int p_147476_2_, int p_147476_3_, TileEntity p_147476_4_)
    {
        if (this.blockExists(p_147476_1_, p_147476_2_, p_147476_3_))
        {
            this.getChunkFromBlockCoords(p_147476_1_, p_147476_3_).setChunkModified();
        }
    }

    /**
     * 计算世界中存在的指定类型实体的数量。
     *
     * @param p_72907_1_ 实体类
     * @return 指定类型实体的数量
     */
    public int countEntities(Class<? extends net.minecraft.entity.Entity> p_72907_1_)
    {
        int i = 0;

        for (int j = 0; j < this.loadedEntityList.size(); ++j)
        {
            Entity entity = (Entity)this.loadedEntityList.get(j);

            if ((!(entity instanceof EntityLiving) || !((EntityLiving)entity).isNoDespawnRequired()) && p_72907_1_.isAssignableFrom(entity.getClass()))
            {
                ++i;
            }
        }

        return i;
    }

    /**
     * 将一组实体添加到已加载实体列表，并加载它们的皮肤。
     *
     * @param p_72868_1_ 要添加的实体列表
     */
    public void addLoadedEntities(List<net.minecraft.entity.Entity> p_72868_1_)
    {
        for (int i = 0; i < p_72868_1_.size(); ++i)
        {
            Entity entity = (Entity)p_72868_1_.get(i);
            if (!MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(entity, this)))
            {
                loadedEntityList.add(entity);
                this.onEntityAdded(entity);
            }
        }
    }

    /**
     * 将一组实体添加到待卸载实体列表中，下一次 World.updateEntities() 时会处理这些实体的卸载。
     *
     * @param p_72828_1_ 要卸载的实体列表
     */
    public void unloadEntities(List<net.minecraft.entity.Entity> p_72828_1_)
    {
        this.unloadedEntityList.addAll(p_72828_1_);
    }

    /**
     * 判断给定的实体是否可以放置在给定方块位置的指定面上。
     *
     * @param p_147472_1_ 要放置的方块
     * @param p_147472_2_ 方块的 X 坐标
     * @param p_147472_3_ 方块的 Y 坐标
     * @param p_147472_4_ 方块的 Z 坐标
     * @param p_147472_5_ 是否进行碰撞检测
     * @param p_147472_6_ 方块的方向
     * @param p_147472_7_ 要放置的实体
     * @param p_147472_8_ 要放置的物品
     * @return 如果实体可以放置，则返回 true；否则返回 false
     */
    public boolean canPlaceEntityOnSide(Block p_147472_1_, int p_147472_2_, int p_147472_3_, int p_147472_4_, boolean p_147472_5_, int p_147472_6_, Entity p_147472_7_, ItemStack p_147472_8_)
    {
        Block block1 = this.getBlock(p_147472_2_, p_147472_3_, p_147472_4_);
        AxisAlignedBB axisalignedbb = p_147472_5_ ? null : p_147472_1_.getCollisionBoundingBoxFromPool(this, p_147472_2_, p_147472_3_, p_147472_4_);
        return axisalignedbb != null && !this.checkNoEntityCollision(axisalignedbb, p_147472_7_) ? false : (block1.getMaterial() == Material.circuits && p_147472_1_ == Blocks.anvil ? true : block1.isReplaceable(this, p_147472_2_, p_147472_3_, p_147472_4_) && p_147472_1_.canReplace(this, p_147472_2_, p_147472_3_, p_147472_4_, p_147472_6_, p_147472_8_));
    }
    /**
     * 获取从一个实体到另一个实体的路径。
     *
     * @param p_72865_1_ 起始实体
     * @param p_72865_2_ 目标实体
     * @param p_72865_3_ 路径寻找的最大范围
     * @param p_72865_4_ 是否允许穿越障碍
     * @param p_72865_5_ 是否允许穿越水域
     * @param p_72865_6_ 是否允许穿越空洞
     * @param p_72865_7_ 是否允许穿越障碍
     * @return 从起始实体到目标实体的路径实体
     */
    public PathEntity getPathEntityToEntity(Entity p_72865_1_, Entity p_72865_2_, float p_72865_3_, boolean p_72865_4_, boolean p_72865_5_, boolean p_72865_6_, boolean p_72865_7_)
    {
        this.theProfiler.startSection("pathfind");
        int i = MathHelper.floor_double(p_72865_1_.posX);
        int j = MathHelper.floor_double(p_72865_1_.posY + 1.0D);
        int k = MathHelper.floor_double(p_72865_1_.posZ);
        int l = (int)(p_72865_3_ + 16.0F);
        int i1 = i - l;
        int j1 = j - l;
        int k1 = k - l;
        int l1 = i + l;
        int i2 = j + l;
        int j2 = k + l;
        ChunkCache chunkcache = new ChunkCache(this, i1, j1, k1, l1, i2, j2, 0);
        PathEntity pathentity = (new PathFinder(chunkcache, p_72865_4_, p_72865_5_, p_72865_6_, p_72865_7_)).createEntityPathTo(p_72865_1_, p_72865_2_, p_72865_3_);
        this.theProfiler.endSection();
        return pathentity;
    }
    /**
     * 获取从一个实体到指定坐标的路径。
     *
     * @param p_72844_1_ 起始实体
     * @param p_72844_2_ 目标 X 坐标
     * @param p_72844_3_ 目标 Y 坐标
     * @param p_72844_4_ 目标 Z 坐标
     * @param p_72844_5_ 路径寻找的最大范围
     * @param p_72844_6_ 是否允许穿越障碍
     * @param p_72844_7_ 是否允许穿越水域
     * @param p_72844_8_ 是否允许穿越空洞
     * @param p_72844_9_ 是否允许穿越障碍
     * @return 从起始实体到目标坐标的路径实体
     */
    public PathEntity getEntityPathToXYZ(Entity p_72844_1_, int p_72844_2_, int p_72844_3_, int p_72844_4_, float p_72844_5_, boolean p_72844_6_, boolean p_72844_7_, boolean p_72844_8_, boolean p_72844_9_)
    {
        this.theProfiler.startSection("pathfind");
        int l = MathHelper.floor_double(p_72844_1_.posX);
        int i1 = MathHelper.floor_double(p_72844_1_.posY);
        int j1 = MathHelper.floor_double(p_72844_1_.posZ);
        int k1 = (int)(p_72844_5_ + 8.0F);
        int l1 = l - k1;
        int i2 = i1 - k1;
        int j2 = j1 - k1;
        int k2 = l + k1;
        int l2 = i1 + k1;
        int i3 = j1 + k1;
        ChunkCache chunkcache = new ChunkCache(this, l1, i2, j2, k2, l2, i3, 0);
        PathEntity pathentity = (new PathFinder(chunkcache, p_72844_6_, p_72844_7_, p_72844_8_, p_72844_9_)).createEntityPathTo(p_72844_1_, p_72844_2_, p_72844_3_, p_72844_4_, p_72844_5_);
        this.theProfiler.endSection();
        return pathentity;
    }

    /**
     * 判断指定位置的方块是否在指定方向上提供红石信号。
     *
     * @param x          方块的 X 坐标
     * @param y          方块的 Y 坐标
     * @param z          方块的 Z 坐标
     * @param directionIn 方块的方向
     * @return 在指定方向上提供的红石信号强度
     */
    public int isBlockProvidingPowerTo(int x, int y, int z, int directionIn)
    {
        return this.getBlock(x, y, z).isProvidingStrongPower(this, x, y, z, directionIn);
    }

    /**
     * 获取给定位置方块接收的最高红石信号强度。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 接收到的最高红石信号强度
     */
    public int getBlockPowerInput(int x, int y, int z)
    {
        byte b0 = 0;
        int l = Math.max(b0, this.isBlockProvidingPowerTo(x, y - 1, z, 0));

        if (l >= 15)
        {
            return l;
        }
        else
        {
            l = Math.max(l, this.isBlockProvidingPowerTo(x, y + 1, z, 1));

            if (l >= 15)
            {
                return l;
            }
            else
            {
                l = Math.max(l, this.isBlockProvidingPowerTo(x, y, z - 1, 2));

                if (l >= 15)
                {
                    return l;
                }
                else
                {
                    l = Math.max(l, this.isBlockProvidingPowerTo(x, y, z + 1, 3));

                    if (l >= 15)
                    {
                        return l;
                    }
                    else
                    {
                        l = Math.max(l, this.isBlockProvidingPowerTo(x - 1, y, z, 4));

                        if (l >= 15)
                        {
                            return l;
                        }
                        else
                        {
                            l = Math.max(l, this.isBlockProvidingPowerTo(x + 1, y, z, 5));
                            return l >= 15 ? l : l;
                        }
                    }
                }
            }
        }
    }

    /**
     * 返回指定方向相反方向上输出的间接信号强度是否大于 0。
     *
     * @param x          方块的 X 坐标
     * @param y          方块的 Y 坐标
     * @param z          方块的 Z 坐标
     * @param directionIn 方块的方向
     * @return 如果在相反方向上输出的间接信号强度大于 0，则返回 true，否则返回 false
     */
    public boolean getIndirectPowerOutput(int x, int y, int z, int directionIn)
    {
        return this.getIndirectPowerLevelTo(x, y, z, directionIn) > 0;
    }

    /**
     * 获取从指定方向到达某方块的间接红石信号强度。
     *
     * @param x          方块的 X 坐标
     * @param y          方块的 Y 坐标
     * @param z          方块的 Z 坐标
     * @param directionIn 方块的方向
     * @return 从指定方向到达某方块的间接红石信号强度
     */
    public int getIndirectPowerLevelTo(int x, int y, int z, int directionIn)
    {
        Block block = this.getBlock(x, y, z);
        return block.shouldCheckWeakPower(this, x, y, z, directionIn) ? this.getBlockPowerInput(x, y, z) : block.isProvidingWeakPower(this, x, y, z, directionIn);
    }

    /**
     * 检查某方块是否间接接收到来自邻近方块的红石信号。此方法用于像 TNT 或门这样的方块，以防止红石信号直接进入它们。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 如果方块或其相邻方块间接接收到红石信号，则返回 true；否则返回 false
     */
    public boolean isBlockIndirectlyGettingPowered(int x, int y, int z)
    {
        return this.getIndirectPowerLevelTo(x, y - 1, z, 0) > 0 ? true : (this.getIndirectPowerLevelTo(x, y + 1, z, 1) > 0 ? true : (this.getIndirectPowerLevelTo(x, y, z - 1, 2) > 0 ? true : (this.getIndirectPowerLevelTo(x, y, z + 1, 3) > 0 ? true : (this.getIndirectPowerLevelTo(x - 1, y, z, 4) > 0 ? true : this.getIndirectPowerLevelTo(x + 1, y, z, 5) > 0))));
    }
    /**
     * 获取在指定位置周围所有方向上的最强间接红石信号强度。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 所有方向上的最强间接红石信号强度（最大为 15）
     */
    public int getStrongestIndirectPower(int x, int y, int z)
    {
        int l = 0;

        for (int i1 = 0; i1 < 6; ++i1)
        {
            int j1 = this.getIndirectPowerLevelTo(x + Facing.offsetsXForSide[i1], y + Facing.offsetsYForSide[i1], z + Facing.offsetsZForSide[i1], i1);

            if (j1 >= 15)
            {
                return 15;
            }

            if (j1 > l)
            {
                l = j1;
            }
        }

        return l;
    }

    /**
     * 获取离指定实体最近的玩家，距离小于指定范围（如果距离小于 0，则忽略距离限制）。
     *
     * @param entityIn 要检查的实体
     * @param distance 最大距离
     * @return 离指定实体最近的玩家，或如果没有找到则返回 null
     */
    public EntityPlayer getClosestPlayerToEntity(Entity entityIn, double distance)
    {
        return this.getClosestPlayer(entityIn.posX, entityIn.posY, entityIn.posZ, distance);
    }

    /**
     * 获取离指定点最近的玩家，距离小于指定范围（距离可以设置为小于 0 以不限制距离）。
     *
     * @param x        X 坐标
     * @param y        Y 坐标
     * @param z        Z 坐标
     * @param distance 最大距离
     * @return 离指定点最近的玩家，或如果没有找到则返回 null
     */
    public EntityPlayer getClosestPlayer(double x, double y, double z, double distance)
    {
        double d4 = -1.0D;
        EntityPlayer entityplayer = null;

        for (int i = 0; i < this.playerEntities.size(); ++i)
        {
            EntityPlayer entityplayer1 = (EntityPlayer)this.playerEntities.get(i);
            double d5 = entityplayer1.getDistanceSq(x, y, z);

            if ((distance < 0.0D || d5 < distance * distance) && (d4 == -1.0D || d5 < d4))
            {
                d4 = d5;
                entityplayer = entityplayer1;
            }
        }

        return entityplayer;
    }

    /**
     * 返回离指定实体最近的脆弱玩家（即不具备伤害免疫的玩家），在指定半径内。如果没有找到则返回 null。
     *
     * @param entityIn 要检查的实体
     * @param distance 最大距离
     * @return 离指定实体最近的脆弱玩家，或如果没有找到则返回 null
     */
    public EntityPlayer getClosestVulnerablePlayerToEntity(Entity entityIn, double distance)
    {
        return this.getClosestVulnerablePlayer(entityIn.posX, entityIn.posY, entityIn.posZ, distance);
    }

    /**
     * 返回离指定点最近的脆弱玩家（即不具备伤害免疫的玩家），在指定半径内。如果没有找到则返回 null。
     *
     * @param p_72846_1_ X 坐标
     * @param p_72846_3_ Y 坐标
     * @param p_72846_5_ Z 坐标
     * @param p_72846_7_ 最大距离
     * @return 离指定点最近的脆弱玩家，或如果没有找到则返回 null
     */
    public EntityPlayer getClosestVulnerablePlayer(double p_72846_1_, double p_72846_3_, double p_72846_5_, double p_72846_7_)
    {
        double d4 = -1.0D;
        EntityPlayer entityplayer = null;

        for (int i = 0; i < this.playerEntities.size(); ++i)
        {
            EntityPlayer entityplayer1 = (EntityPlayer)this.playerEntities.get(i);

            if (!entityplayer1.capabilities.disableDamage && entityplayer1.isEntityAlive())
            {
                double d5 = entityplayer1.getDistanceSq(p_72846_1_, p_72846_3_, p_72846_5_);
                double d6 = p_72846_7_;

                if (entityplayer1.isSneaking())
                {
                    d6 = p_72846_7_ * 0.800000011920929D;
                }

                if (entityplayer1.isInvisible())
                {
                    float f = entityplayer1.getArmorVisibility();

                    if (f < 0.1F)
                    {
                        f = 0.1F;
                    }

                    d6 *= (double)(0.7F * f);
                }

                if ((p_72846_7_ < 0.0D || d5 < d6 * d6) && (d4 == -1.0D || d5 < d4))
                {
                    d4 = d5;
                    entityplayer = entityplayer1;
                }
            }
        }

        return entityplayer;
    }

    /**
     * 通过名称查找世界中的玩家。
     *
     * @param name 玩家名称
     * @return 名称匹配的玩家，如果没有找到则返回 null
     */
    public EntityPlayer getPlayerEntityByName(String name)
    {
        for (int i = 0; i < this.playerEntities.size(); ++i)
        {
            EntityPlayer entityplayer = (EntityPlayer)this.playerEntities.get(i);

            if (name.equals(entityplayer.getCommandSenderName()))
            {
                return entityplayer;
            }
        }

        return null;
    }
    /**
     * 通过 UUID 查找世界中的玩家。
     *
     * @param uuid 玩家 UUID
     * @return UUID 匹配的玩家，如果没有找到则返回 null
     */
    public EntityPlayer func_152378_a(UUID uuid)
    {
        for (int i = 0; i < this.playerEntities.size(); ++i)
        {
            EntityPlayer entityplayer = (EntityPlayer)this.playerEntities.get(i);

            if (uuid.equals(entityplayer.getUniqueID()))
            {
                return entityplayer;
            }
        }

        return null;
    }

    /**
     * 如果在多人游戏模式下，发送退出的数据包。
     */
    @SideOnly(Side.CLIENT)
    public void sendQuittingDisconnectingPacket() {}

    /**
     * 检查会话锁文件是否被其他进程修改
     *
     * @throws MinecraftException 如果会话锁检查失败
     */
    public void checkSessionLock() throws MinecraftException
    {
        this.saveHandler.checkSessionLock();
    }
    /**
     * 更新世界总时间。
     *
     * @param p_82738_1_ 增加的时间
     */
    @SideOnly(Side.CLIENT)
    public void func_82738_a(long p_82738_1_)
    {
        this.worldInfo.incrementTotalWorldTime(p_82738_1_);
    }

    /**
     * 从 level.dat 文件中检索世界种子。
     *
     * @return 世界种子
     */
    public long getSeed()
    {
        return provider.getSeed();
    }
    /**
     * 获取世界的总时间。
     *
     * @return 世界总时间
     */
    public long getTotalWorldTime()
    {
        return this.worldInfo.getWorldTotalTime();
    }
    /**
     * 获取当前世界时间。
     *
     * @return 当前世界时间
     */
    public long getWorldTime()
    {
        return provider.getWorldTime();
    }

    /**
     * 设置世界时间。
     *
     * @param time 要设置的世界时间
     */
    public void setWorldTime(long time)
    {
        provider.setWorldTime(time);
    }

    /**
     * 返回出生点的坐标。
     *
     * @return 出生点坐标
     */
    public ChunkCoordinates getSpawnPoint()
    {
        return provider.getSpawnPoint();
    }
    /**
     * 设置出生点位置。
     *
     * @param p_72950_1_ X 坐标
     * @param p_72950_2_ Y 坐标
     * @param p_72950_3_ Z 坐标
     */
    public void setSpawnLocation(int p_72950_1_, int p_72950_2_, int p_72950_3_)
    {
        provider.setSpawnPoint(p_72950_1_, p_72950_2_, p_72950_3_);
    }

    /**
     * 生成实体并加载周围的区块。
     *
     * @param entityIn 要生成的实体
     */
    @SideOnly(Side.CLIENT)
    public void joinEntityInSurroundings(Entity entityIn)
    {
        int i = MathHelper.floor_double(entityIn.posX / 16.0D);
        int j = MathHelper.floor_double(entityIn.posZ / 16.0D);
        byte b0 = 2;

        for (int k = i - b0; k <= i + b0; ++k)
        {
            for (int l = j - b0; l <= j + b0; ++l)
            {
                this.getChunkFromChunkCoords(k, l);
            }
        }

        if (!this.loadedEntityList.contains(entityIn))
        {
            if (!MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(entityIn, this)))
            {
                this.loadedEntityList.add(entityIn);
            }
        }
    }

    /**
     * 检查玩家是否可以挖掘特定的方块。进行 "出生点安全区" 检查。
     *
     * @param player 玩家
     * @param x 方块的X坐标
     * @param y 方块的Y坐标
     * @param z 方块的Z坐标
     * @return 如果可以挖掘方块，则返回 true，否则返回 false
     */
    public boolean canMineBlock(EntityPlayer player, int x, int y, int z)
    {
        return provider.canMineBlock(player, x, y, z);
    }
    /**
     * 检查玩家是否可以挖掘特定的方块。
     *
     * @param par1EntityPlayer 玩家
     * @param par2 方块的X坐标
     * @param par3 方块的Y坐标
     * @param par4 方块的Z坐标
     * @return 总是返回 true
     */
    public boolean canMineBlockBody(EntityPlayer par1EntityPlayer, int par2, int par3, int par4)
    {
        return true;
    }

    /**
     * 向所有跟踪该实体的玩家发送 Packet 38（实体状态）数据包。
     *
     * @param entityIn 目标实体
     * @param p_72960_2_ 状态字节
     */
    public void setEntityState(Entity entityIn, byte p_72960_2_) {}

    /**
     * 获取此世界使用的 IChunkProvider 实例。
     *
     * @return 当前使用的 IChunkProvider 实例
     */
    public IChunkProvider getChunkProvider()
    {
        return this.chunkProvider;
    }

    /**
     * 向 blockEventCache 添加一个方块事件。下一次 tick() 调用时，指定方块会用给定的参数调用 onBlockEvent 处理程序。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param blockIn 触发事件的方块
     * @param eventId 事件 ID
     * @param eventParameter 事件参数
     */
    public void addBlockEvent(int x, int y, int z, Block blockIn, int eventId, int eventParameter)
    {
        blockIn.onBlockEventReceived(this, x, y, z, eventId, eventParameter);
    }

    /**
     * 返回当前世界的保存处理程序。
     *
     * @return 当前保存处理程序
     */
    public ISaveHandler getSaveHandler()
    {
        return this.saveHandler;
    }

    /**
     * 获取世界的 WorldInfo 实例。
     *
     * @return 当前世界的信息
     */
    public WorldInfo getWorldInfo()
    {
        return this.worldInfo;
    }

    /**
     * 获取游戏规则的实例。
     *
     * @return 当前世界的 GameRules 实例
     */
    public GameRules getGameRules()
    {
        return this.worldInfo.getGameRulesInstance();
    }

    /**
     * 更新标志，指示是否所有玩家都在睡觉。
     */
    public void updateAllPlayersSleepingFlag() {}
    /**
     * 获取加权的雷电强度。
     *
     * @param p_72819_1_ 过渡因子
     * @return 加权的雷电强度
     */
    public float getWeightedThunderStrength(float p_72819_1_)
    {
        return (this.prevThunderingStrength + (this.thunderingStrength - this.prevThunderingStrength) * p_72819_1_) * this.getRainStrength(p_72819_1_);
    }

    /**
     * 设置雷电强度。
     *
     * @param p_147442_1_ 雷电强度
     */
    @SideOnly(Side.CLIENT)
    public void setThunderStrength(float p_147442_1_)
    {
        this.prevThunderingStrength = p_147442_1_;
        this.thunderingStrength = p_147442_1_;
    }


    public float getRainStrength(float p_72867_1_)
    {
        return this.prevRainingStrength + (this.rainingStrength - this.prevRainingStrength) * p_72867_1_;
    }

    /**
     * 设置降雨强度。
     *
     * @param strength 降雨强度值
     */
    @SideOnly(Side.CLIENT)
    public void setRainStrength(float strength)
    {
        this.prevRainingStrength = strength;
        this.rainingStrength = strength;
    }

    /**
     * 返回当前雷电强度（与降雨强度加权后的值）是否大于 0.9。
     *
     * @return 如果雷电强度大于 0.9，返回 true；否则返回 false
     */
    public boolean isThundering()
    {
        return (double)this.getWeightedThunderStrength(1.0F) > 0.9D;
    }

    /**
     * 返回当前降雨强度是否大于 0.2。
     *
     * @return 如果降雨强度大于 0.2，返回 true；否则返回 false
     */
    public boolean isRaining()
    {
        return (double)this.getRainStrength(1.0F) > 0.2D;
    }
    /**
     * 检查指定坐标是否可以发生闪电。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 如果可以发生闪电，返回 true；否则返回 false
     */
    public boolean canLightningStrikeAt(int x, int y, int z)
    {
        if (!this.isRaining())
        {
            return false;
        }
        else if (!this.canBlockSeeTheSky(x, y, z))
        {
            return false;
        }
        else if (this.getPrecipitationHeight(x, z) > y)
        {
            return false;
        }
        else
        {
            BiomeGenBase biomegenbase = this.getBiomeGenForCoords(x, z);
            return biomegenbase.getEnableSnow() ? false : (this.func_147478_e(x, y, z, false) ? false : biomegenbase.canSpawnLightningBolt());
        }
    }

    /**
     * 检查指定坐标的生物群系降水值是否非常高。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 如果该坐标的湿度值非常高，返回 true；否则返回 false
     */
    public boolean isBlockHighHumidity(int x, int y, int z)
    {
        return provider.isBlockHighHumidity(x, y, z);
    }

    /**
     * 使用 MapStorage 将给定的字符串 ID 分配给给定的 MapDataBase，并删除任何现有的相同 ID 的数据。
     *
     * @param p_72823_1_ 数据 ID
     * @param p_72823_2_ 要分配的 MapDataBase 实例
     */
    public void setItemData(String p_72823_1_, WorldSavedData p_72823_2_)
    {
        this.mapStorage.setData(p_72823_1_, p_72823_2_);
    }


    /**
     * 从磁盘加载与给定字符串 ID 对应的现有 MapDataBase，实例化给定的 Class，或者如果没有此文件则返回 null。
     *
     * @param p_72943_1_ 要实例化的类
     * @param p_72943_2_ 数据 ID
     * @return 加载的 WorldSavedData 实例，如果不存在则返回 null
     */
    public WorldSavedData loadItemData(Class<? extends net.minecraft.world.WorldSavedData> p_72943_1_, String p_72943_2_)
    {
        return this.mapStorage.loadData(p_72943_1_, p_72943_2_);
    }

    /**
     * 从 MapStorage 获取一个唯一的新数据 ID，用于给定的前缀，并将 idCounts 映射保存到 'idcounts' 文件。
     *
     * @param p_72841_1_ 前缀
     * @return 唯一的新数据 ID
     */
    public int getUniqueDataId(String p_72841_1_)
    {
        return this.mapStorage.getUniqueDataId(p_72841_1_);
    }
    /**
     * 播放广播声音到所有跟踪该世界的世界访问者。
     *
     * @param p_82739_1_ 声音 ID
     * @param p_82739_2_ X 坐标
     * @param p_82739_3_ Y 坐标
     * @param p_82739_4_ Z 坐标
     * @param p_82739_5_ 音量
     */
    public void playBroadcastSound(int p_82739_1_, int p_82739_2_, int p_82739_3_, int p_82739_4_, int p_82739_5_)
    {
        for (int j1 = 0; j1 < this.worldAccesses.size(); ++j1)
        {
            ((IWorldAccess)this.worldAccesses.get(j1)).broadcastSound(p_82739_1_, p_82739_2_, p_82739_3_, p_82739_4_, p_82739_5_);
        }
    }


    /**
     * 播放一个附加特效（Auxiliary Special Effect）到所有跟踪该世界的世界访问者，具体到指定坐标。
     *
     * @param p_72926_1_ 特效 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param p_72926_5_ 特效参数
     */
    public void playAuxSFX(int p_72926_1_, int x, int y, int z, int p_72926_5_)
    {
        this.playAuxSFXAtEntity((EntityPlayer)null, p_72926_1_, x, y, z, p_72926_5_);
    }

    /**
     * 播放附加特效（Auxiliary Special Effect），并将事件信息记录到崩溃报告中（如果发生异常）。
     *
     * @param player 事件来源玩家（可以为 null）
     * @param p_72889_2_ 特效 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param p_72889_6_ 特效数据
     */
    public void playAuxSFXAtEntity(EntityPlayer player, int p_72889_2_, int x, int y, int z, int p_72889_6_)
    {
        try
        {
            for (int j1 = 0; j1 < this.worldAccesses.size(); ++j1)
            {
                ((IWorldAccess)this.worldAccesses.get(j1)).playAuxSFX(player, p_72889_2_, x, y, z, p_72889_6_);
            }
        }
        catch (Throwable throwable)
        {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Playing level event");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Level event being played");
            crashreportcategory.addCrashSection("Block coordinates", CrashReportCategory.getLocationInfo(x, y, z));
            crashreportcategory.addCrashSection("Event source", player);
            crashreportcategory.addCrashSection("Event type", Integer.valueOf(p_72889_2_));
            crashreportcategory.addCrashSection("Event data", Integer.valueOf(p_72889_6_));
            throw new ReportedException(crashreport);
        }
    }

    /**
     * 返回当前世界的高度。
     *
     * @return 世界高度
     */
    public int getHeight()
    {
        return provider.getHeight();
    }

    /**
     * 返回当前世界的实际高度。
     *
     * @return 世界实际高度
     */
    public int getActualHeight()
    {
        return provider.getActualHeight();
    }

    /**
     * 根据输入值设置世界随机种子的状态。
     *
     * @param p_72843_1_ X 坐标
     * @param p_72843_2_ Y 坐标
     * @param p_72843_3_ Z 坐标
     * @return 设置后的随机数生成器
     */
    public Random setRandomSeed(int p_72843_1_, int p_72843_2_, int p_72843_3_)
    {
        long l = (long)p_72843_1_ * 341873128712L + (long)p_72843_2_ * 132897987541L + this.getWorldInfo().getSeed() + (long)p_72843_3_;
        this.rand.setSeed(l);
        return this.rand;
    }

    /**
     * 返回指定类型的最近结构的位置。如果没有找到，则返回 null。
     *
     * @param type 结构类型
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 最近结构的位置，若未找到则返回 null
     */
    public ChunkPosition findClosestStructure(String type, int x, int y, int z)
    {
        return this.getChunkProvider().func_147416_a(this, type, x, y, z);
    }

    /**
     * 设置是否扩展了 Chunk 缓存的级别（仅客户端使用）。
     *
     * @return 总是返回 false
     */
    @SideOnly(Side.CLIENT)
    public boolean extendedLevelsInChunkCache()
    {
        return false;
    }

    /**
     * 返回用于渲染天空的地平线高度（仅客户端使用）。
     *
     * @return 地平线高度
     */
    @SideOnly(Side.CLIENT)
    public double getHorizon()
    {
        return provider.getHorizon();
    }

    /**
     * 将世界的基本统计信息添加到给定的崩溃报告中。
     *
     * @param report 崩溃报告实例
     * @return 崩溃报告类别
     */
    public CrashReportCategory addWorldInfoToCrashReport(CrashReport report)
    {
        CrashReportCategory crashreportcategory = report.makeCategoryDepth("Affected level", 1);
        crashreportcategory.addCrashSection("Level name", this.worldInfo == null ? "????" : this.worldInfo.getWorldName());
        crashreportcategory.addCrashSectionCallable("All players", new Callable()
        {
            private static final String __OBFID = "CL_00000143";
            public String call()
            {
                return World.this.playerEntities.size() + " total; " + World.this.playerEntities.toString();
            }
        });
        crashreportcategory.addCrashSectionCallable("Chunk stats", new Callable()
        {
            private static final String __OBFID = "CL_00000144";
            public String call()
            {
                return World.this.chunkProvider.makeString();
            }
        });

        try
        {
            this.worldInfo.addToCrashReport(crashreportcategory);
        }
        catch (Throwable throwable)
        {
            crashreportcategory.addCrashSectionThrowable("Level Data Unobtainable", throwable);
        }

        return crashreportcategory;
    }

    /**
     * 开始（或继续）在给定坐标的块上进行破坏，并设置当前破坏值。
     *
     * @param p_147443_1_ 破坏块的 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param blockDamage 当前的破坏进度（0 到 10 的值）
     */
    public void destroyBlockInWorldPartially(int p_147443_1_, int x, int y, int z, int blockDamage)
    {
        for (int j1 = 0; j1 < this.worldAccesses.size(); ++j1)
        {
            IWorldAccess iworldaccess = (IWorldAccess)this.worldAccesses.get(j1);
            iworldaccess.destroyBlockPartially(p_147443_1_, x, y, z, blockDamage);
        }
    }

    /**
     * 返回一个包含当前日期的日历对象。
     *
     * @return 当前日期的日历对象
     */
    public Calendar getCurrentDate()
    {
        if (this.getTotalWorldTime() % 600L == 0L)
        {
            this.theCalendar.setTimeInMillis(MinecraftServer.getSystemTimeMillis());
        }

        return this.theCalendar;
    }
    /**
     * 创建烟花效果（仅客户端使用）。
     *
     * @param x 烟花的 X 坐标
     * @param y 烟花的 Y 坐标
     * @param z 烟花的 Z 坐标
     * @param motionX X 方向的运动速度
     * @param motionY Y 方向的运动速度
     * @param motionZ Z 方向的运动速度
     * @param compund 包含烟花效果数据的 NBTTagCompound
     */
    @SideOnly(Side.CLIENT)
    public void makeFireworks(double x, double y, double z, double motionX, double motionY, double motionZ, NBTTagCompound compund) {}
    /**
     * 获取世界的记分板实例。
     *
     * @return 世界的记分板
     */
    public Scoreboard getScoreboard()
    {
        return this.worldScoreboard;
    }
    /**
     * 检查并更新与给定块相邻的方块的状态。
     *
     * @param x X 坐标
     * @param yPos Y 坐标
     * @param z Z 坐标
     * @param blockIn 当前方块
     */
    public void func_147453_f(int x, int yPos, int z, Block blockIn)
    {
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS)
        {
            int i1 = x + dir.offsetX;
            int y  = yPos + dir.offsetY;
            int j1 = z + dir.offsetZ;
            Block block1 = this.getBlock(i1, y, j1);

            block1.onNeighborChange(this, i1, y, j1, x, yPos, z);
            if (block1.isNormalCube(this, i1, y, j1))
            {
                i1 += dir.offsetX;
                y  += dir.offsetY;
                j1 += dir.offsetZ;
                Block block2 = this.getBlock(i1, y, j1);

                if (block2.getWeakChanges(this, i1, y, j1))
                {
                    block2.onNeighborChange(this, i1, y, j1, x, yPos, z);
                }
            }
        }
    }
    /**
     * 根据给定坐标计算光照强度。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 光照强度
     */
    public float func_147462_b(double x, double y, double z)
    {
        return this.func_147473_B(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z));
    }
    /**
     * 根据给定坐标计算光照强度。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 光照强度
     */
    public float func_147473_B(int x, int y, int z)
    {
        float f = 0.0F;
        boolean flag = this.difficultySetting == EnumDifficulty.HARD;

        if (this.blockExists(x, y, z))
        {
            float f1 = this.getCurrentMoonPhaseFactor();
            f += MathHelper.clamp_float((float)this.getChunkFromBlockCoords(x, z).inhabitedTime / 3600000.0F, 0.0F, 1.0F) * (flag ? 1.0F : 0.75F);
            f += f1 * 0.25F;
        }

        if (this.difficultySetting == EnumDifficulty.EASY || this.difficultySetting == EnumDifficulty.PEACEFUL)
        {
            f *= (float)this.difficultySetting.getDifficultyId() / 2.0F;
        }

        return MathHelper.clamp_float(f, 0.0F, flag ? 1.5F : 1.0F);
    }
    /**
     * 更新所有静态实体的状态。
     */
    public void func_147450_X()
    {
        Iterator iterator = this.worldAccesses.iterator();

        while (iterator.hasNext())
        {
            IWorldAccess iworldaccess = (IWorldAccess)iterator.next();
            iworldaccess.onStaticEntitiesChanged();
        }
    }


    /* ======================================== FORGE START =====================================*/
    /**
     * 将单个 TileEntity 添加到世界中。
     *
     * @param entity 要添加的 TileEntity 实例。
     */
    public void addTileEntity(TileEntity entity)
    {
        List dest = field_147481_N ? addedTileEntityList : loadedTileEntityList;
        if(entity.canUpdate())
        {
            dest.add(entity);
        }
    }

    /**
     * 确定给定块在指定侧面是否被认为是固体的。用于放置逻辑。
     *
     * @param x 块的 X 坐标
     * @param y 块的 Y 坐标
     * @param z 块的 Z 坐标
     * @param side 问题面的方向
     * @return 如果该面是固体的，则返回 true
     */
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side)
    {
        return isSideSolid(x, y, z, side, false);
    }

    /**
     * 确定给定块在指定侧面是否被认为是固体的。用于放置逻辑。
     *
     * @param x 块的 X 坐标
     * @param y 块的 Y 坐标
     * @param z 块的 Z 坐标
     * @param side 问题面的方向
     * @param _default 如果块不存在时的默认返回值
     * @return 如果该面是固体的，则返回 true
     */
    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean _default)
    {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000)
        {
            return _default;
        }

        Chunk chunk = this.chunkProvider.provideChunk(x >> 4, z >> 4);
        if (chunk == null || chunk.isEmpty())
        {
            return _default;
        }
        return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
    }

    /**
     * 获取此世界的持久化区块。
     *
     * @return 持久化区块的集合映射
     */
    public ImmutableSetMultimap<ChunkCoordIntPair, Ticket> getPersistentChunks()
    {
        return ForgeChunkManager.getPersistentChunksFor(this);
    }

    /**
     * 重新添加此方法，因为它被删除了，提供了非常有用的辅助功能。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 该块的光照不透明度
     */
    public int getBlockLightOpacity(int x, int y, int z)
    {
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000)
        {
            return 0;
        }

        if (y < 0 || y >= 256)
        {
            return 0;
        }

        return getChunkFromChunkCoords(x >> 4, z >> 4).func_150808_b(x & 15, y, z & 15);
    }

    /**
     * 返回自我分类为指定生物类型的实体数量。
     *
     * @param type 实体的生物类型
     * @param forSpawnCount 是否用于生成计数
     * @return 指定生物类型的实体数量
     */
    public int countEntities(EnumCreatureType type, boolean forSpawnCount)
    {
        int count = 0;
        for (int x = 0; x < loadedEntityList.size(); x++)
        {
            if (((Entity)loadedEntityList.get(x)).isCreatureType(type, forSpawnCount))
            {
                count++;
            }
        }
        return count;
    }
}
