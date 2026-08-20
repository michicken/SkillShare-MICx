package dev.skillshare;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.skillshare.net.SkillShareClient;
import dev.skillshare.net.TokenProvider;
import dev.skillshare.roster.ZbSidebar;
import dev.skillshare.roster.ZombiesTracker;
import dev.skillshare.skill.TeamSkillTracker;

import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkillShare 核心编排：连接 + join + 技能 CD 收发 + LR 聊天栏共享。
 *
 * <p>行为边界（用户确认）：只做聊天栏事件与 websocket 技能 CD 收发，
 * 不画任何 HUD/面板。队友技能状态仅落内存，不渲染。</p>
 */
public class SkillShareCore {

    public static final String SERVER_URL = "wss://zombie.nienie.fun/zombies/ws";
    public static final String TOKEN_URL = "https://zombie.nienie.fun/zombies/token";

    private static final Pattern LR_RELEASE_CHAT =
            Pattern.compile("^You struck (.+) with your Lightning Rod Skill!$");

    /** 自己释放 Heal（可能群奶）：
     *  "You healed yourself with your Heal Skill!" 或
     *  "You healed yourself and N teammate with your Heal Skill!" */
    private static final Pattern HEAL_SELF_RELEASE =
            Pattern.compile("^You healed yourself(?: and (\\d+) teammate[s]?)? with your Heal Skill!$",
                    Pattern.CASE_INSENSITIVE);
    /** 队友释放 Heal 奶到了我："<名> healed you with their Heal Skill!" */
    private static final Pattern HEAL_TEAMMATE_RELEASED_ON_ME =
            Pattern.compile("^(\\w{1,16}) healed you with their Heal Skill!$", Pattern.CASE_INSENSITIVE);

    private static final long ROSTER_CHECK_MS = 1000L;
    private static final long UPDATE_INTERVAL_MS = 250L;
    private static final long JOIN_ACK_TIMEOUT_MS = 4000L;
    private static final long HEARTBEAT_INTERVAL_MS = 5000L;
    private static final int INBOUND_BUDGET_PER_TICK = 256;
    /** 入房后延迟公布共享玩家列表，等服务端把同房队友聚齐，避免开局只有自己。 */
    private static final long ROOM_ANNOUNCE_DELAY_MS = 2000L;

    private final Gson gson = new Gson();
    private final SkillShareClient client = new SkillShareClient();
    private final TokenProvider tokenProvider = new TokenProvider(TOKEN_URL);
    private final TeamSkillTracker skillTracker = new TeamSkillTracker();
    private ZombiesTracker tracker;

    private final Set<String> lastRoster = new HashSet<String>();
    private final Set<String> roomMembers = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, TeammateSkill> teammateSkills = new HashMap<String, TeammateSkill>();

    private String selfName;
    private long lastRosterCheck = 0L;
    private long lastStateSend = 0L;
    private long lastHeartbeat = 0L;
    private boolean joined = false;
    private long pendingJoinAt = 0L;
    private long stateSeq = 0L;
    private net.minecraft.world.World skillWorld;

    /** 本局入房后是否已打印过共享玩家列表（一次会话只打印一次）。 */
    private boolean roomJoinAnnounced = false;
    /** 入房后延迟公布的时刻；0 = 尚未调度。 */
    private long roomJoinAnnounceAt = 0L;
    /** 语言不匹配提示每局只弹一次。 */
    private boolean languagePromptShown = false;
    private net.minecraft.world.World languageWorld;
    private int tickCounter = 0;

    /** 队友技能状态：仅落内存，不渲染。 */
    private static final class TeammateSkill {
        TeamSkillTracker.State state;
        String name;
        int remainingS;
        long receivedMs;
        /** 当前冷却是否已发过"剩5秒"提醒（一次冷却只提醒一次；就绪/换技能后重置）。 */
        boolean announcedAt5;
    }

    /** 由 @Mod 入口在 FML init 调用；注册事件并启动连接。 */
    public void init() {
        tracker = ZombiesTracker.get();
        ZombiesTracker.ensureRegistered();
        MinecraftForge.EVENT_BUS.register(this);
        client.start(SERVER_URL, tokenProvider);
    }

    public void stop() {
        client.stop();
        MinecraftForge.EVENT_BUS.unregister(this);
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onChat(ClientChatReceivedEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (e.type != 0 && e.type != 1) return;   // 忽略 actionbar
        try {
            String text = EnumChatFormatting.getTextWithoutFormattingCodes(e.message.getUnformattedText());
            if (text == null) return;
            long now = System.currentTimeMillis();
            String self = mc.thePlayer.getName();

            // --- LR 释放（自己）---
            Matcher lr = LR_RELEASE_CHAT.matcher(text);
            if (lr.matches()) {
                String target = lr.group(1).trim();
                boolean isCount = target.matches("\\d+");
                int struckCount = isCount ? Integer.parseInt(target) : -1;
                String struckName = isCount ? null : target;

                skillTracker.markReleased("Lightning Rod", now);   // 释放 LR：起 20s 固定冷却倒计时
                announceLr(self, struckCount, struckName);   // 自己聊天栏提示
                client.sendLrRelease(self, struckCount, struckName);   // 上报队友（统一走服务端去重）
                lastStateSend = 0L;                              // 下个 tick 立即带 lr 精确倒计时
                return;
            }

            // --- Heal 释放（自己 or 队友奶到我）---
            Matcher hs = HEAL_SELF_RELEASE.matcher(text);
            if (hs.matches()) {
                // 自己释放 Heal：可能群奶（and N teammate）。上报 + 本地提示。
                skillTracker.markReleased("Heal", now);   // 释放 Heal：起 30s 固定冷却倒计时
                announceHeal(self);
                client.sendHealRelease(self, now);   // 释放者上报（服务端统一去重）
                return;
            }
            Matcher ht = HEAL_TEAMMATE_RELEASED_ON_ME.matcher(text);
            if (ht.matches()) {
                String healer = ht.group(1).trim();
                // 自己被奶：默认不上报（释放者会自己上报）。
                // 情况2：若释放者没装共享 mod（不在房间内）→ 代他上报这次释放。
                if (healer != null && !healer.equals(self) && !roomMembers.contains(healer)) {
                    client.sendHealRelease(healer, now);   // 代报（服务端去重，多个被奶者都发也不会重复广播）
                }
                return;
            }
        } catch (Throwable ignored) { }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        long now = System.currentTimeMillis();
        selfName = mc.thePlayer.getName();

        // 0. 重连后需重新 join
        if (client.consumeReconnected()) {
            joined = false;
            roomMembers.clear();
            lastRosterCheck = 0L;
        }

        // 0.5 语言不匹配检测（中文 AA 局 → 点击切换英文）
        if (++tickCounter % 10 == 0) checkLanguageAndPrompt(mc);

        // 1. 心跳
        if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeat = now;
            client.sendHeartbeat();
        }

        // 2. 处理入站消息
        processInbound();

        // 2.5 延迟公布共享玩家列表：等 room 成员聚齐后再打印（避免开局只有自己）
        if (joined && !roomJoinAnnounced && roomJoinAnnounceAt > 0L && now >= roomJoinAnnounceAt) {
            roomJoinAnnounced = true;
            roomJoinAnnounceAt = 0L;
            announceRoomJoin();
        }

        // 3. 本地槽位观察（自己的技能 CD）
        observeLocalSkill(mc.thePlayer, now);

        // 4. join（roster 变化/超时重试）
        checkRosterAndJoin(mc, now);

        // 5. 状态上报（在房间里）
        if (joined && roomMembers.contains(selfName) && now - lastStateSend >= UPDATE_INTERVAL_MS) {
            lastStateSend = now;
            sendState(mc.thePlayer, now);
        }
    }

    private void observeLocalSkill(net.minecraft.entity.player.EntityPlayer p, long now) {
        if (skillWorld != p.worldObj) {
            skillWorld = p.worldObj;
            skillTracker.reset();
        }
        ItemStack stack = p.inventory.getStackInSlot(TeamSkillTracker.SLOT_INDEX);
        String ready = null;
        boolean gray = false;
        int count = -1;
        if (stack != null) {
            ready = TeamSkillTracker.canonicalSkillName(stack.getDisplayName());
            count = stack.stackSize;
            gray = stack.getItem() == Items.dye;
        }
        skillTracker.observe(ready, gray, count, now);
    }

    private void checkRosterAndJoin(Minecraft mc, long now) {
        Set<String> roster = tracker.roster();
        if (roster == null) roster = Collections.emptySet();
        boolean solo = roster.size() <= 1
                || (roster.size() == 1 && roster.contains(selfName));
        if (solo) {
            if (joined || pendingJoinAt > 0L || !roomMembers.isEmpty() || !lastRoster.isEmpty()) {
                resetSession();
            }
            return;
        }
        if (joined && !roomMembers.contains(selfName)) {
            joined = false;
            roomMembers.clear();
        }
        if (pendingJoinAt > 0L && now - pendingJoinAt < JOIN_ACK_TIMEOUT_MS) return;
        if (joined && roster.equals(lastRoster)) return;
        if (!joined || !roster.equals(lastRoster)) {
            if (joined) resetSession();
            lastRoster.clear();
            lastRoster.addAll(roster);
        }
        if (lastRoster.isEmpty()) return;
        if (client.sendJoin(selfName, lastRoster,
                tracker.isInAA() ? "AA" : "Zombies", tracker.round())) {
            joined = false;
            pendingJoinAt = now;
        } else {
            pendingJoinAt = 0L;
        }
    }

    private void sendState(net.minecraft.entity.player.EntityPlayer p, long now) {
        TeamSkillTracker.Snapshot skill = skillTracker.snapshot(now);
        boolean isLr = skill.known && skill.skillName != null && "Lightning Rod".equals(skill.skillName);
        long lrReleasedAt = isLr ? skillTracker.releasedAtMs() : 0L;
        long lrReadyAt = isLr ? skillTracker.cooldownUntilMs() : 0L;
        client.sendState(selfName, p.posX, p.posY, p.posZ, skill, ++stateSeq, lrReleasedAt, lrReadyAt);
    }

    private void processInbound() {
        String raw;
        int budget = INBOUND_BUDGET_PER_TICK;
        while (budget-- > 0 && (raw = client.poll()) != null) {
            try {
                JsonObject m = gson.fromJson(raw, JsonObject.class);
                if (m == null) continue;
                String type = m.has("type") ? m.get("type").getAsString() : null;
                if (type == null) continue;
                if ("room_info".equals(type)) {
                    roomMembers.clear();
                    com.google.gson.JsonArray members = m.getAsJsonArray("members");
                    if (members != null) {
                        for (com.google.gson.JsonElement el : members) {
                            roomMembers.add(el.getAsString());
                        }
                    }
                    joined = selfName != null && roomMembers.contains(selfName);
                    if (joined) pendingJoinAt = 0L;
                    if (joined && !roomJoinAnnounced && roomJoinAnnounceAt == 0L) {
                        // 开局入房后延迟公布：等服务端把同房队友都聚齐，避免开局只有自己的假象
                        roomJoinAnnounceAt = System.currentTimeMillis() + ROOM_ANNOUNCE_DELAY_MS;
                    }
                    // 只保留在房内的队友技能状态
                    java.util.Iterator<Map.Entry<String, TeammateSkill>> it = teammateSkills.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, TeammateSkill> en = it.next();
                        if (en.getKey() == null || en.getKey().equals(selfName)
                                || !roomMembers.contains(en.getKey())) it.remove();
                    }
                } else if ("state".equals(type)) {
                    String name = m.has("name") ? m.get("name").getAsString() : null;
                    if (name == null || name.equals(selfName)) continue;
                    TeammateSkill ts = teammateSkills.get(name);
                    if (ts == null) { ts = new TeammateSkill(); teammateSkills.put(name, ts); }
                    com.google.gson.JsonElement skillEl = m.get("skill");
                    if (skillEl != null && skillEl.isJsonObject()) {
                        JsonObject sk = skillEl.getAsJsonObject();
                        // 收到新 skill 名（换技能/新技能）→ 重置提醒标记
                        String newName = sk.has("name") && !sk.get("name").isJsonNull()
                                ? sk.get("name").getAsString() : null;
                        if (newName != null && !newName.equals(ts.name)) ts.announcedAt5 = false;
                        ts.state = TeamSkillTracker.State.valueOf(sk.has("state")
                                ? sk.get("state").getAsString() : "UNKNOWN");
                        ts.name = newName;
                        ts.remainingS = sk.has("remaining_s") ? sk.get("remaining_s").getAsInt() : 0;
                        maybeAnnounceCooldownSoon(name, ts);
                    }
                    ts.receivedMs = System.currentTimeMillis();
                } else if ("member_left".equals(type)) {
                    String name = m.has("name") ? m.get("name").getAsString() : null;
                    if (name != null) {
                        roomMembers.remove(name);
                        teammateSkills.remove(name);
                        if (name.equals(selfName)) resetSession();
                    }
                } else if ("lr_release".equals(type)) {
                    String name = m.has("name") ? m.get("name").getAsString() : null;
                    if (name == null || name.equals(selfName)) continue;
                    int struckCount = m.has("struck_count") ? m.get("struck_count").getAsInt() : -1;
                    String struckName = m.has("struck_name") && !m.get("struck_name").isJsonNull()
                            ? m.get("struck_name").getAsString() : null;
                    announceLr(name, struckCount, struckName);
                } else if ("heal_release".equals(type)) {
                    // 服务端去重后下发的 Heal 释放（可能是房内释放者自报，或房外被奶者代报）
                    String healer = m.has("healer") && !m.get("healer").isJsonNull()
                            ? m.get("healer").getAsString() : null;
                    if (healer == null || healer.equals(selfName)) continue;
                    announceHeal(healer);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 聊天栏打印 "[SkillShare] <名> 使用了LR命中了 ... "。 */
    private void announceLr(String name, int struckCount, String struckName) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GOLD).append("[SkillShare] ")
          .append(EnumChatFormatting.WHITE).append(name)
          .append(EnumChatFormatting.YELLOW).append(" 使用了LR命中了 ");
        if (struckCount >= 0) {
            sb.append(EnumChatFormatting.RED).append(struckCount).append(" 个敌人");
        } else if (struckName != null) {
            sb.append(EnumChatFormatting.LIGHT_PURPLE).append(struckName);
        }
        mc.thePlayer.addChatMessage(new ChatComponentText(sb.toString()));
    }

    /** 聊天栏打印 "[SkillShare] <名> 使用了HEAL"。 */
    private void announceHeal(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GOLD).append("[SkillShare] ")
          .append(EnumChatFormatting.WHITE).append(name)
          .append(EnumChatFormatting.YELLOW).append(" 使用了HEAL");
        mc.thePlayer.addChatMessage(new ChatComponentText(sb.toString()));
    }

    /**
     * 队友技能冷却剩约 5 秒 → 聊天栏提醒一次。冷却从 6→5 的沿触发；
     * 一次 COOLING 只提醒一次，技能就绪/换技能后重置。Heal 冷却 30s 也走这套通用判定。
     */
    private void maybeAnnounceCooldownSoon(String playerName, TeammateSkill ts) {
        if (ts.state == TeamSkillTracker.State.READY || ts.state == TeamSkillTracker.State.UNKNOWN) {
            ts.announcedAt5 = false;   // 就绪/未知：重装引信
            return;
        }
        // COOLING
        if (ts.name == null || ts.remainingS <= 0) return;   // 无技能名或已就绪，跳过
        if (ts.announcedAt5) return;                         // 本次冷却已提醒过
        if (ts.remainingS <= 5) {
            ts.announcedAt5 = true;
            announceCooldownSoon(playerName, ts.name, ts.remainingS);
        }
    }

    /** 聊天栏打印 "[SkillShare] <名> 的 <技能> 冷却剩余 N 秒"。 */
    private void announceCooldownSoon(String playerName, String skillName, int remainingS) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GOLD).append("[SkillShare] ")
          .append(EnumChatFormatting.WHITE).append(playerName)
          .append(EnumChatFormatting.YELLOW).append(" 的 ")
          .append(EnumChatFormatting.AQUA).append(skillName)
          .append(EnumChatFormatting.YELLOW).append(" 冷却剩余 ")
          .append(EnumChatFormatting.RED).append(remainingS).append(" 秒");
        mc.thePlayer.addChatMessage(new ChatComponentText(sb.toString()));
    }

    /** 聊天栏打印 "[SkillShare] 本局有 N 位玩家共享技能：<名字列表>"（仅自己可见）。 */
    private void announceRoomJoin() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || roomMembers.isEmpty()) return;
        List<String> sorted = new ArrayList<String>(roomMembers);
        java.util.Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GOLD).append("[SkillShare] ")
          .append(EnumChatFormatting.YELLOW).append("本局有 ")
          .append(EnumChatFormatting.AQUA).append(sorted.size())
          .append(EnumChatFormatting.YELLOW).append(" 位玩家共享技能：")
          .append(EnumChatFormatting.WHITE).append(String.join(", ", sorted));
        mc.thePlayer.addChatMessage(new ChatComponentText(sb.toString()));
    }

    /** 检测中文 AA 局（语言不匹配），每局弹一次点击切换到英文的提示。 */
    private void checkLanguageAndPrompt(Minecraft mc) {
        if (mc.theWorld != languageWorld) {
            languageWorld = mc.theWorld;
            languagePromptShown = false;
        }
        if (!ZbSidebar.read(mc).isChineseAlienArcadium()) return;
        if (languagePromptShown) return;
        languagePromptShown = true;
        showLanguagePrompt(mc);
    }

    private void showLanguagePrompt(Minecraft mc) {
        ChatComponentText message = new ChatComponentText(
                "§8[§6SkillShare§8] §e您当前游戏为中文模式！§cSkillShare 不在中文模式下起效，§f请点击切换为英文模式 ");
        ChatComponentText action = new ChatComponentText("§a§l[切换英文]");
        ChatStyle style = new ChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lang english"))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText("§e点击执行 /lang english")));
        action.setChatStyle(style);
        message.appendSibling(action);
        mc.thePlayer.addChatMessage(message);
    }

    private void resetSession() {
        joined = false;
        pendingJoinAt = 0L;
        lastRoster.clear();
        roomMembers.clear();
        teammateSkills.clear();
        roomJoinAnnounced = false;
        roomJoinAnnounceAt = 0L;
    }
}