# SkillShare-MICx

Hypixel Zombies 技能共享插件（Minecraft 1.8.9 Forge 客户端）。

在多人对局中，通过 WebSocket 实时与队友共享 **Lightning Rod（LR）** 与 **Heal** 的
释放事件和冷却状态，并在聊天栏打印 `[SkillShare]` 事件，让每个队友都看得见技能动态。

> 轻量独立分支：只做「聊天栏事件 + 技能共享」，不画任何 HUD/面板，体积小、可独立使用。

---

## 功能

- **LR 命中共享**：你释放 LR 命中敌人时，聊天栏打印并同步上报队友（命中 N 个敌人 / 命中的具名怪）。
- **Heal 释放共享**：你释放 Heal，或被队友奶到，聊天栏同步广播这次治疗。
- **队友冷却同步**：通过 WebSocket 实时接收队友技能的冷却状态。
- **冷却剩 5 秒提醒**：队友技能冷却剩余 ≤5 秒时，聊天栏提醒一次
  （`[SkillShare] <名> 的 <技能> 冷却剩余 N 秒`，一次冷却只提醒一次）。

## 安装

1. 前置：Minecraft **1.8.9** + **Forge 11.15.1.2318**。
2. 把 `SkillShare-MICx-<版本>.jar` 放入 `.minecraft/mods/`。
3. 参与 Zombies 对局即可自动生效，无需任何配置。

> 本项目通过独立 WebSocket 端点通信，**不修改游戏网络包、不读取他人私密数据**。

## 构建

```bash
./gradlew build
# 产物：build/libs/SkillShare-MICx-<版本>.jar
```

## 第三方接入 / 协议（开发者）

想让自己的 mod 与 SkillShare 用户同房间互操作、共享 LR/Heal 释放与冷却信息？
只要你的客户端连上同一个 WebSocket 端点、用签名 token 鉴权即可，**无需本插件的任何场内代码**。
完整协议、消息 schema 与公钥见 **[PROTOCOL.md](./PROTOCOL.md)**。这里是最小接入路径：

```
1. 取 token   GET https://zombie.nienie.fun/zombies/token
              → {token, exp, sig}（用 PROTOCOL.md 里的内嵌公钥对 "token:exp" 验签）
2. 连 ws      wss://zombie.nienie.fun/zombies/ws
3. 入房       send {"type":"join","token":<上一步整包>,"self":"<你的ID>",
                     "roster":["<你的ID>","队友名",...],"map":"Dead End","round":1}
              server 回 {"type":"room_info","members":[..]} 即入房成功
4. 上报/接收  join 后每 ~250ms 发 state{...}；释放 LR 时发 lr_release{...}；
              Heal 时发 heal_release{...}；同时接收队友的 state/lr_release/heal_release
```

- WebSocket 端点：`wss://zombie.nienie.fun/zombies/ws`
- Token 地址：`https://zombie.nienie.fun/zombies/token`
- **关键消息**：`room_info`（入房确认）、`state`（技能冷却）、`lr_release`、`heal_release`
- 连接保活：周期发 `heartbeat`；掉线重连后必须重新 `join`；重连**不要**发 `leave`

## License

Apache-2.0。详见 [LICENSE](./LICENSE)。
