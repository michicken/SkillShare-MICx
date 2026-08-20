package dev.skillshare.skill;

import java.util.Locale;

/**
 * 槽位 5 技能状态采样器（独立版，逻辑与主 MICx-toolkit TeamSkillTracker 一致）。
 *
 * <p>倒地期间 Hypixel 会暂时清空玩家物品栏，因此 UNKNOWN 观察不会擦除
 * 已确认的技能或本地冷却计时。灰色染料只有连续两次严格递减才会被确认
 * 为技能冷却，避免把倒地后的错误物品数量当成 CD。</p>
 *
 * <p>实测（ZombiesLogger ndjson 2026-07-29）Hypixel 技能槽位表现：
 * 就绪：LR = {@code blaze_rod "Lightning Rod Skill" count 1}，
 * Heal = {@code golden_apple "Heal Skill" count 1}（非 dye）；
 * 冷却：物品被换成 {@code dye}，但名字<em>不变</em>（仍含 "X Skill"），
 * count 从 ~20 递减到 1 = 剩余冷却秒数；无技能：{@code dye "Skill"}。</p>
 */
public final class TeamSkillTracker {

    public static final int SLOT_INDEX = 4;

    /** 固定冷却时长：Lightning Rod 20s、Heal 30s。释放后本地起档，不依赖槽位物品，
     *  倒地清栏也不会把这套倒计时清掉。 */
    public static final long LR_DURATION_MS = 20_000L;
    public static final long HEAL_DURATION_MS = 30_000L;

    private static long durationMsFor(String canonical) {
        return "Heal".equals(canonical) ? HEAL_DURATION_MS : LR_DURATION_MS;
    }

    public enum State {
        UNKNOWN, READY, COOLING
    }

    /** 渲染和 SkillShare payload 共用的不可变视图。 */
    public static final class Snapshot {
        public final boolean known;
        public final State state;
        public final String skillName;
        public final int remainingSeconds;

        private Snapshot(boolean known, State state, String skillName, int remainingSeconds) {
            this.known = known;
            this.state = state;
            this.skillName = skillName;
            this.remainingSeconds = remainingSeconds;
        }

        public boolean isReady() { return known && state == State.READY; }
        public boolean isCooling() { return known && state == State.COOLING; }

        public static Snapshot of(State state, String skillName, int remainingSeconds) {
            return new Snapshot(skillName != null, state, skillName, Math.max(0, remainingSeconds));
        }
    }

    private State state = State.UNKNOWN;
    private String skillName;
    /** 固定倒计时截止时刻（wall-clock ms）；冷却期间由 release/observe 起档。 */
    private long cooldownUntilMs;
    /** 本局最近一次释放确认时刻（wall-clock ms）；0 = 尚未发生。 */
    private long releasedAtMs = 0L;
    /** 是否已见过“就绪物品”：下一次见到冷却物品应视为一次“新释放”起固定档。 */
    private boolean armed = false;

    /**
     * 处理一次槽位 5 观察；未知物品只推进自然过期，不会清除已知状态。
     * "名字命中技能"并不等于就绪——必须同时看物品是否为 dye。
     */
    public synchronized void observe(String readySkillName, boolean grayDye, int count, long nowMs) {
        expireIfNeeded(nowMs);
        String canonical = readySkillName != null ? canonicalSkillName(readySkillName) : null;

        // 已在固定倒计时中：由 wall-clock 走完，屏蔽物品栏噪声/倒地清栏的干扰
        if (state == State.COOLING && cooldownUntilMs > nowMs) {
            if (canonical != null) skillName = canonical;   // 仅校准技能名
            return;
        }

        if (canonical == null) {
            // 无技能 / 倒地清栏 / 名字不命中：不清除状态，让固定倒计时自然走完（倒地也不出错）
            return;
        }

        if (grayDye) {
            // 冷却物品（dye，名字仍命中）：仅在“就绪后第一次见到”或“本观侧首次判定到”时起固定档。
            if (armed || state == State.UNKNOWN) {
                state = State.COOLING;
                skillName = canonical;
                armed = false;
                if (releasedAtMs == 0) releasedAtMs = nowMs;
                cooldownUntilMs = nowMs + durationMsFor(canonical);   // 固定 20/30s，从见到冷却起算
            }
            // else：我们自己的固定倒计时刚走完的残留 dye，忽略，保持就绪
            return;
        }

        // 就绪：技能物品本身（blaze_rod / golden_apple）
        state = State.READY;
        skillName = canonical;
        cooldownUntilMs = 0L;
        releasedAtMs = 0L;
        armed = true;
    }

    /** 只读当前状态；剩余秒数按本地截止时间平滑计算。 */
    public synchronized Snapshot snapshot(long nowMs) {
        expireIfNeeded(nowMs);
        if (state == State.UNKNOWN || skillName == null) {
            return new Snapshot(false, State.UNKNOWN, null, 0);
        }
        if (state == State.COOLING) {
            return new Snapshot(true, State.COOLING, skillName, remainingSeconds(nowMs));
        }
        return new Snapshot(true, State.READY, skillName, 0);
    }

    /** 自放 LR/技能本次冷却截止时刻（wall-clock ms）；非冷却时返回 0。 */
    public synchronized long cooldownUntilMs() { return state == State.COOLING ? cooldownUntilMs : 0L; }

    /** 最近一次 READY→COOLING 释放确认时刻（wall-clock ms）；未发生返回 0。 */
    public synchronized long releasedAtMs() { return releasedAtMs; }

    /** 外部信号（释放聊天事件）确认一次释放：记录精确时刻 + 技能名，并使下次见到冷却物品即起固定档。 */
    public synchronized void markReleased(String canonicalName, long nowMs) {
        if (canonicalName != null && !canonicalName.isEmpty()) skillName = canonicalName;
        releasedAtMs = nowMs;   // 精确释放时刻
        armed = true;           // 下一次见到冷却物品按固定时长（LR 20s / Heal 30s）起档
    }

    public synchronized void reset() {
        state = State.UNKNOWN;
        skillName = null;
        cooldownUntilMs = 0L;
        releasedAtMs = 0L;
        armed = false;
    }

    private void expireIfNeeded(long nowMs) {
        if (state == State.COOLING && cooldownUntilMs <= nowMs) {
            state = skillName == null ? State.UNKNOWN : State.READY;
            cooldownUntilMs = 0L;
        }
    }

    private int remainingSeconds(long nowMs) {
        long remainingMs = Math.max(0L, cooldownUntilMs - nowMs);
        return (int) Math.min(Integer.MAX_VALUE, (remainingMs + 999L) / 1000L);
    }

    /** 将服务器显示名归一化为 SkillShare 协议中的两个稳定名称。 */
    public static String canonicalSkillName(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("§.", "").trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("lightning rod") || lower.contains("lighting rod")
                || s.contains("闪电棒") || s.contains("烈焰棒")) {
            return "Lightning Rod";
        }
        if (lower.equals("heal") || lower.contains("heal skill")
                || lower.contains("golden apple") || lower.contains("golden_apple")
                || s.contains("金苹果")) {
            return "Heal";
        }
        return null;
    }
}