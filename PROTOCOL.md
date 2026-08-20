# SkillShare 开放协议 (v1)

> 面向第三方 Minecraft mod / 客户端开发者。本文档描述 SkillShare-MICx 与同房间用户
> 实时同步 **Lightning Rod（LR）/ Heal 释放事件与冷却状态** 的 WebSocket 协议。
> 任何能访问公网端点、并按本协议做加签 token 的客户端，均可与本插件用户同房间互操作。

---

## 1. 端点与鉴权总览

| 项 | 值 | 说明 |
|---|---|---|
| WebSocket 端点 | `wss://zombie.nienie.fun/zombies/ws` | TLS，需正确 SNI |
| Token 地址 | `https://zombie.nienie.fun/zombies/token` | GET，返回签名 token |
| 鉴权方式 | 服务端 RSA 私钥签名，客户端用**内嵌公钥**验签 | 私钥不落客户端/不落 jar |
| Token 有效期 | 1 小时（`exp` 为 epoch 秒） | 建议过期前 5 分钟预刷新 |
| 连接保活 | 客户端周期发 `heartbeat`；掉线自动重连后必须重新 join | |

### 1.1 Token 获取

```
GET https://zombie.nienie.fun/zombies/token
→ 200  {"token":"<opaque>","exp":<unix秒>,"sig":"<base64 RSA-SHA256>"}
```

验签：对字符串 `"<token>:<exp>"` 用下方公钥做 `SHA256withRSA` 验签，
签名合法且未过期即接受。无法取到/验签有效 token 时，`join` 会被服务端拒绝。

### 1.2 服务端公钥（PEM，RSA-2048）

```
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1Mq147intdgg6rL2x4P/
pJxmkWHl1x8GUME7khtrA+/dLp+N0FeXnSfyg06JWvRgX3uW7t9A/GU481YKph8V
yviHmRJtgbYkT9LnXazlKR7uEnvkH5J8lVrYfvqzaMneb+bWndqPuGzR8c5563em
XnVBZgI2YjLtoabrlZi01z+C2HsrngP8yxH8xTIdOswajpFMU2HbVPTvMO3QOHE5
dFVOevnbH/q3QdDujmD0qkgJtflbthJoKTRe2FD0I9do600uoxUXELaSdd9v9JNP
d8xddF9Mv90fSIM+D58Zl5PEW7Uz4XeYYcsAl1eTweKONm3DIo2A3ZGwc4+wts0S
BwIDAQAB
```

---

## 2. 连接流程（状态机）

```
connect(wss://…/ws)
  └─ onConnected：
        └─ [可选] 后台 GET /token 预取
        └─ 玩家进入对局(roster 确定) 后：send join{token,self,roster,map,round}
              └─ server 回 room_info {members:[…]}
                   ├─ self ∈ members  → inRoom=true（join 成功）
                   └─ self ∉ members → join 未确认，稍后重发
        └─ 之后周期发 state{…}（约 250ms）；释放 LR/Heal 时发 lr_release/heal_release
```

> **重连后服务端没有会话，必须重新 join**，否则收不到队友状态。

---

## 3. 消息类型总表

方向：**C→S** = 客户端发送；**S→C** = 服务端广播/单发。

| 类型 | 方向 | 说明 |
|---|---|---|
| `join` | C→S | 携带 token + 本名 + roster，申请入队同步房间 |
| `heartbeat` | C→S | 保活 |
| `leave` | C→S | 离开房间（重连时不要发，否则形成断开循环） |
| `state` | C→S / S→C | 玩家实时状态（位置/技能 CD） |
| `room_info` | S→C | 房间成员快照（join 确认） |
| `member_left` | S→C | 某成员离开 |
| `lr_release` | C→S / S→C | 某玩家释放 Lightning Rod 的即时事件 |
| `heal_release` | C→S / S→C | 某玩家释放 Heal 的即时事件（服务端按 healer+时间窗去重） |

---

## 4. 消息 Schema

> 所有数值均为 JSON number；坐标为 Minecraft 世界坐标。字符串一律 JSON 转义。

### 4.1 `join`（C→S）

```json
{
  "type": "join",
  "token": {"token":"…","exp":1735689600,"sig":"…"},
  "self": "PlayerName",
  "roster": ["PlayerName","Ally1","Ally2"],
  "map": "Dead End",
  "round": 1
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | object | 直接嵌入第 1.1 节返回的整个 json 对象 |
| `self` | string | 本玩家名（房间内唯一标识） |
| `roster` | string[] | 想同步的队友名单（房间成员集合） |
| `map` | string | 地图名，仅展示 |
| `round` | int | 当前轮/回合，仅展示 |

### 4.2 `room_info`（S→C，join 确认）

```json
{"type":"room_info","members":["PlayerName","Ally1"]}
```

`members` 含自己即确认入房。成员缺省时可按需清理本地缓存。

### 4.3 `state`（双向，周期广播）

```json
{
  "type": "state",
  "name": "PlayerName",
  "state_seq": 123,
  "pos": {"x":1.0,"y":64.0,"z":2.0},
  "skill": {"slot":4,"state":"COOLING","name":"Heal","remaining_s":7},
  "lr": {"released":1735689600123,"ready_at":1735689612000}
}
```

| 字段 | 类型 | 可缺省 | 说明 |
|---|---|---|---|
| `name` | string | 否 | 玩家名 |
| `state_seq` | int | 否 | 单调递增序列号，用于乱序丢弃 |
| `pos.x/y/z` | double | 否 | 位置 |
| `skill.slot` | int | 仅 skill 存在 | 技能槽位（约定 Java 槽索引 4） |
| `skill.state` | string | 仅 skill 存在 | `READY` / `COOLING` / `UNKNOWN` |
| `skill.name` | string\|nil | 仅 skill 存在 | 规范化技能名（`Lightning Rod` / `Heal`） |
| `skill.remaining_s` | int | 仅 skill 存在 | 剩余冷却秒（仅 COOLING 有意义） |
| `lr` | object | 是 | LR 释放通道，见 4.4 |

> **技能判定约定（Hypixel Zombies 实测）**：就绪时槽位为 `blaze_rod`(LR) /
> `golden_apple`(Heal)；冷却时槽位物品变为 `dye` 但**名字仍为 "X Skill"**，
> count 递减 = 剩余冷却秒；无技能占位为 `dye` 名 "Skill"。判定冷却必须看物品
> 是否为 dye，不能只看名字。

### 4.4 `lr` 对象（嵌在 `state` 内）

```json
{"released":1735689600123,"ready_at":1735689612000}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `released` | long | 最近一次释放 LR 的 wall-clock 毫秒时间戳（=0 表示从未释放当前技能） |
| `ready_at` | long | >0：LR 冷却精确截止的毫秒时间；==0：LR 已就绪（显式清队友侧残留冷却） |

> `ready_at` 是发送方本地时钟的绝对时间，跨机时钟偏差由接收方自行处理；
> 若 `lr` 不新鲜，建议回退用 `skill.remaining_s` 的**相对剩余秒**。

### 4.5 `lr_release`（双向，即时事件）

命中 N 个敌人：

```json
{"type":"lr_release","name":"PlayerName","struck_count":4}
```

命中单个具名怪：

```json
{"type":"lr_release","name":"PlayerName","struck_name":"Worm"}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 释放者玩家名 |
| `struck_count` | int (可选) | 命中敌人数量；>=0 时存在 |
| `struck_name` | string (可选) | 单个具名怪名（Worm / The Old One / Zombie / Clown / Iron Golem …） |

`struck_count` 与 `struck_name` 二选一。

### 4.6 `heal_release`（双向，即时事件）

释放者可上报，被奶者也可**代报**（当释放者未装共享 mod、不在房间内）。
服务端按 `healer + 时间窗` 去重，多个被奶者同时代报也不会重复广播。

```json
{"type":"heal_release","healer":"PlayerName","released":1735689600123}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `healer` | string | 释放 Heal 的玩家名 |
| `released` | long | 释放发生的 wall-clock 毫秒时间戳（供服务端去重） |

### 4.7 `member_left`（S→C）

```json
{"type":"member_left","name":"PlayerName"}
```

### 4.8 `heartbeat` / `leave`（C→S）

```json
{"type":"heartbeat"}
{"type":"leave"}
```

> 注意：**重连后不要发 `leave`**。只在本局真正结束时由客户端主动退出才发。
> 重连分支应只清本地会话状态并重新 `join`，否则会触发 connect→leave→断开→reconnect 循环。

---

## 5. 第三方接入最小示例（伪代码）

```js
// 1. 取 token
const token = await GET("https://zombie.nienie.fun/zombies/token");
verifySig(token);                    // 用上节公钥对 token+":"+exp 验 SIG

// 2. 连 ws
const ws = connect("wss://zombie.nienie.fun/zombies/ws");

// 3. join（roster 确定后）
ws.send({type:"join", token, self: me, roster:[me,...], map, round});

// 4. 处理
ws.onMessage = (msg) => {
  switch (msg.type) {
    case "room_info":
      members = msg.members; inRoom = members.includes(me); break;
    case "state":
      if (msg.skill) teammateSkill[msg.name] = msg.skill;        // 冷却状态
      if (msg.lr && msg.lr.ready_at) teammateLrUntil[msg.name] = msg.lr.ready_at;
    case "lr_release":
      notify(msg.name + " 使用了LR命中 " + (msg.struck_count ?? msg.struck_name)); break;
    case "heal_release":
      notify(msg.healer + " 使用了HEAL"); break;
  }
};

// 5. 周期上报自身（约 250ms）
setInterval(() => ws.send({type:"state", name:me, state_seq:++seq,
  pos, skill:{slot:4,state, name, remaining_s}, lr:{released, ready_at}}), 250);

// 6. 释放 LR 时
ws.send({type:"lr_release", name:me, struck_count:n});   // 或 struck_name

// 7. 释放 Heal（或被未装mod的释放者奶到）时
ws.send({type:"heal_release", healer: meOrHealerName, released: Date.now()});
```

---

## 6. 给服务端维护者的开放建议

1. **限频**：按连接/IP 限制 `join`/`state` 发送速率，防第三方刷屏。
2. **roster 校验**：校验 `roster` 是否含大量无效名，防空房间滥用。
3. **token 绑定**：目前 token 与玩家身份绑定较弱，对外可考虑把 `self` 写进 token 校验。
4. **去重**：`heal_release` 用 `healer + 时间窗` 在服务端做幂等去重。
