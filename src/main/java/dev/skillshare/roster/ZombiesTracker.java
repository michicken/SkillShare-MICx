package dev.skillshare.roster;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对局状态（独立精简版）：仅驱动 SkillShare 所需的
 * 队伍名单（roster）、地图/回合、isInZombies/isInAA。
 *
 * <p>数据源 = 侧边计分板（{@link ZbSidebar}），5 次/秒刷新。相较主 mod 的完整版
 * ZombiesTracker 去掉了倒地状态机、powerup、声学 LR 计数、武器/金币等——
 * 那些 SkillShare 用不到。
 */
public final class ZombiesTracker {

    private static final ZombiesTracker INSTANCE = new ZombiesTracker();
    private static boolean registered;

    public static ZombiesTracker get() { return INSTANCE; }

    /** 幂等注册到总线。 */
    public static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    private ZombiesTracker() { }

    private final ConcurrentHashMap<String, String> playerStatusMap = new ConcurrentHashMap<String, String>();
    private volatile boolean inZombies, inAA;
    private volatile int round;                 // 0 = 未知
    private volatile long roundStartMs;
    private WeakReference<net.minecraft.world.World> lastWorld = new WeakReference<net.minecraft.world.World>(null);
    private int tick;

    public boolean isInZombies()  { return inZombies; }
    public boolean isInAA()       { return inAA; }
    public int round()            { return round; }
    public long roundStartMs()    { return roundStartMs; }
    /** 当前队伍名单（侧边栏有状态行的玩家名）。 */
    public Set<String> roster()   { return new HashSet<String>(playerStatusMap.keySet()); }
    /** 某玩家状态（alive/down/dead/quit），无则 null。 */
    public String statusOf(String name) { return name == null ? null : playerStatusMap.get(name); }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;

        // 世界切换 → 对局状态复位
        net.minecraft.world.World w = mc.theWorld;
        if (lastWorld.get() != w) {
            lastWorld = new WeakReference<net.minecraft.world.World>(w);
            reset();
        }

        tick++;
        if (tick % 4 != 0) return;    // 计分板 5 次/秒

        ZbSidebar sb = ZbSidebar.read(mc);
        inZombies = sb.isZombies();
        // "Map: Alien Arcadium" 开局后侧边栏换成 Round/Area 布局，只锁存不清除，由 reset()(换世界)清除
        if (inZombies && sb.isAlienArcadium()) inAA = true;
        if (!inZombies) return;

        if (sb.round > 0) onRound(sb.round);

        // 从计分板采集队伍名单：有 sidebar 状态行的玩家（alive/down/dead/quit）
        if (mc.thePlayer.sendQueue != null) {
            for (NetworkPlayerInfo info : new java.util.ArrayList<NetworkPlayerInfo>(mc.thePlayer.sendQueue.getPlayerInfoMap())) {
                if (info.getGameProfile() == null) continue;
                String n = info.getGameProfile().getName();
                String st = sb.playerStatus(n);
                if (st != null) playerStatusMap.put(n, st);
            }
        }
    }

    /** 接受递增回合；上一局 R25 后收到新局 R1 时视为新局重开回合。 */
    private synchronized void onRound(int n) {
        if (n <= 0 || n > 105) return;
        if (n < round) {
            // 新局重开（回合倒退）
            round = n;
            roundStartMs = System.currentTimeMillis();
            return;
        }
        if (n > round) {
            round = n;
            roundStartMs = System.currentTimeMillis();
        }
    }

    private void reset() {
        inZombies = false;
        inAA = false;
        round = 0;
        roundStartMs = 0L;
        playerStatusMap.clear();
    }
}