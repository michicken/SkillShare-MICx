package dev.skillshare.roster;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 侧边计分板读取（独立版，逻辑与主 MICx-toolkit ZbSidebar 一致）。
 *
 * <p>MC 1.8 侧边栏每行渲染 = Team prefix + entry + Team suffix。Hypixel 把
 * entry 设为 emoji 占位符，真实文字全在 Team prefix/suffix —— 必须用
 * {@link ScorePlayerTeam#formatPlayerName} 拼回三段。行数可超 15，"#" 开头
 * entry 要过滤。
 */
public class ZbSidebar {

    private static final Pattern ROUND = Pattern.compile("Round\\s*:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAP = Pattern.compile("Map\\s*:?\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZOMBIES_LEFT = Pattern.compile("Zombies(?:\\s*Left)?\\s*:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    public String title = "";
    /** 渲染行（去色码、去占位符），自上而下。 */
    public final List<String> lines = new ArrayList<String>();
    /** 渲染行（保留色码与图标，供 ☠/✖ 状态判定）。 */
    public final List<String> rawLines = new ArrayList<String>();
    public int round = -1;
    public String map = null;
    public int zombiesLeft = -1;

    public boolean isZombies() {
        return title != null && (title.toUpperCase().contains("ZOMBIES") || title.contains("僵尸"));
    }

    /**
     * AA 专属区域名（"Area:" 行）——开局后全程存在，判 AA 一定为真。
     * 实测 "Map: Alien Arcadium" 整局只出现 1 帧（开局前大厅），2 次/秒采样
     * 极易完全错过 → 这些游乐设施名是外星游乐园独有的，全程可读。
     */
    private static final String[] AA_AREAS = {
        "park entrance", "ferris wheel", "roller coaster", "bumper cars"
    };
    private static final String CHINESE_AA_MAP = "外星游乐园";

    public boolean isAlienArcadium() {
        if (map != null && map.toLowerCase().contains("alien")) return true;
        for (String l : lines) {
            String low = l.toLowerCase();
            if (low.contains("alien arcadium")) return true;
            if (l.contains(CHINESE_AA_MAP)) return true;
            if (low.contains("area")) {
                for (String a : AA_AREAS) {
                    if (low.contains(a)) return true;
                }
            }
        }
        return false;
    }

    /** 高置信中文局信号：本地化计分板写 "地图：外星游乐园"，开局前即可检测到。 */
    public boolean isChineseAlienArcadium() {
        if (map != null && map.contains(CHINESE_AA_MAP)) return true;
        for (String line : lines) {
            if (line != null && line.contains(CHINESE_AA_MAP)) return true;
        }
        return false;
    }

    /** 玩家行状态：alive / down / dead / quit；侧边栏没有该玩家行时返回 null。 */
    public String playerStatus(String name) {
        if (name == null || name.isEmpty()) return null;
        for (int i = 0; i < lines.size(); i++) {
            String clean = lines.get(i);
            if (!isPlayerLine(clean, name)) continue;
            String raw = rawLines.get(i);
            if (clean.contains("QUIT")) return "quit";
            if (raw.contains("✖")) return "dead";
            if (clean.contains("DEAD") && !clean.contains("REVIVE")) return "dead";
            if (raw.contains("\u2620") || clean.contains("REVIVE")) return "down";
            return "alive";
        }
        return null;
    }

    public int playerGold(String name) {
        if (name == null || name.isEmpty()) return -1;
        for (int i = 0; i < lines.size(); i++) {
            String clean = lines.get(i);
            if (!isPlayerLine(clean, name)) continue;
            int colonIdx = clean.lastIndexOf(':');
            if (colonIdx < 0) continue;
            String numPart = clean.substring(colonIdx + 1).trim().replace(",", "");
            try {
                return Integer.parseInt(numPart);
            } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    static boolean isPlayerLine(String line, String name) {
        if (line == null || name == null || name.isEmpty()) return false;
        int start = 0;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) start++;
        if (!line.regionMatches(start, name, 0, name.length())) return false;
        int end = start + name.length();
        while (end < line.length() && Character.isWhitespace(line.charAt(end))) end++;
        return end < line.length() && line.charAt(end) == ':';
    }

    /** 读取 display slot 1。永不抛异常。 */
    public static ZbSidebar read(Minecraft mc) {
        ZbSidebar out = new ZbSidebar();
        try {
            if (mc.theWorld == null) return out;
            Scoreboard board = mc.theWorld.getScoreboard();
            if (board == null) return out;
            ScoreObjective obj = board.getObjectiveInDisplaySlot(1);
            if (obj == null) return out;

            String title = EnumChatFormatting.getTextWithoutFormattingCodes(obj.getDisplayName());
            out.title = title != null ? title : "";

            List<Score> scores = new ArrayList<Score>(board.getSortedScores(obj));
            for (int i = scores.size() - 1; i >= 0; i--) {
                Score s = scores.get(i);
                String entry = s.getPlayerName();
                if (entry == null) entry = "";
                if (entry.startsWith("#")) continue;
                ScorePlayerTeam team = board.getPlayersTeam(entry);
                String rendered = ScorePlayerTeam.formatPlayerName(team, entry);
                if (rendered == null) rendered = entry;

                out.rawLines.add(rendered);
                String clean = EnumChatFormatting.getTextWithoutFormattingCodes(rendered);
                out.lines.add(stripPlaceholders(clean));
            }

            for (String line : out.lines) {
                if (line.isEmpty()) continue;
                Matcher m;
                if (out.round == -1 && (m = ROUND.matcher(line)).find()) {
                    try {
                        int r = Integer.parseInt(m.group(1));
                        if (r > 0 && r <= 999) out.round = r;
                    } catch (NumberFormatException ignored) { }
                    continue;
                }
                if (out.map == null && (m = MAP.matcher(line)).find()) {
                    out.map = m.group(1).trim();
                    continue;
                }
                if (out.zombiesLeft == -1 && (m = ZOMBIES_LEFT.matcher(line)).find()) {
                    try {
                        out.zombiesLeft = Integer.parseInt(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 去掉 Hypixel 占位符：emoji 代理对与杂项符号区段。 */
    private static String stripPlaceholders(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) continue;
            if (c >= 0x2600 && c <= 0x27BF) continue;
            if (c >= 0x2B00 && c <= 0x2BFF) continue;
            if (c >= 0x2300 && c <= 0x23FF) continue;
            if (c == 0xFE0F || c == 0x20E3) continue;
            b.append(c);
        }
        return b.toString().trim();
    }
}