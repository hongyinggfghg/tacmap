<div align="center">

<img src="logo.png" width="220" alt="Xaero Tactical Map"/>

# Xaero Tactical Map

**基于 Xaero 地图的战术地图模组 · Minecraft 1.20.1 Forge**

ATAK 风格的路点战术面板、双制式方位读数、军事符号标注与小队协同
当前版本 **4.0.15** · 依赖 Xaero's Minimap 26.5.0 / World Map 1.46.0

</div>

---

## 功能特性

### 战术 HUD（K 键「战术目标」面板）
- 按 K 开关，屏幕角落显示最近的 **Xaero 原生标记点**列表（当前维度、当前标记组），按距离排序
- 每条带完整战术读数：**距离 / 密位（SBW火炮）/ 朝向（0~360）/ 高度差**


### 战术标注（大地图左侧工具栏）
- 军事符号面板：我方12 种+ 敌方 8 种（敌情 / 可疑 / 伏击 / 狙击 / 打击目标 / 集结点 / 基地 / 星形），

### 小队系统与会话同步（J 打开面板）
- 创建 / 加入小队，每队固定颜色（我方蓝、敌情永远红）
- 局域网 / 服务器同步**未装模组的服务器可正常进入**（无法共享标记）
- 标注按维度隔离；退出服务器自动清理小队残留
- 服务器 OP 可用 `/tacmap squad disband <小队名|all>` 解散小队
- **导入 / 导出 JSON 收纳在小队面板二级菜单**

## 安装

1. 安装 Minecraft 1.20.1 + Forge 47.4.22+
2. 安装 Xaero's Minimap（26.5.0+）与 Xaero's World Map（1.46.0+
3. 把本模组 jar 放入 `mods` 文件夹（客户端即可，无需服务端强制安装）
4. 进游戏：M 打开地图看工具栏，J 打开小队面板，K 开关战术 HUD

## 按键

| 按键 | 功能 |
|------|------|
| **K** | 开关「战术目标」HUD 面板 |
| **J** | 打开 / 关闭小队面板 |
| M    | 打开 Xaero 世界地图（本模组标注工具挂在地图界面左侧） |

## 配置

**游戏内**：模组列表 → Xaero Tactical Map → Config（设置），界面含显示开关与底部一排快捷钮。

**配置文件**：`config/xaerotacmap-client.toml`，主要键：

| 键 | 默认 | 说明 |
|----|------|------|
| `enabled` | true | HUD 总开关 |
| `displayMode` | HOTKEY | HOTKEY / ALWAYS / MAP_OPEN |
| `maxEntries` | 3 | 面板最多显示条数 |
| `updateIntervalTicks` | 2 | 刷新间隔（tick） |
| `corner` / `offsetX` / `offsetY` / `scale` | 左上 | 面板锚点与缩放 |
| `colorDots` / `showCoordinates` / `decimals` | true/true/1 | 色点 / 坐标 / 小数位 |
| `includeDisabledWaypoints` | false | 收集 Xaero 中已勾掉（禁用）的路点 |
| `includeTemporaryWaypoints` | false | 收集**临时导航点**（目的地） |
| `includeDeathpoints` | false | 收集 Xaero 自动记录的死亡点 |
| `tacticalLine` / `lineMidLabel` / `hoverPanel` | true | 地图虚线 / 中点标注 / 悬停面板 |
| `showChipReadout` | true | 标注名称芯片第二行读数（朝/盘/距） |
| `debugBar` | false | 诊断条 |


## 常见问题

**Q：按 K 只显示「战术目标： 就绪」，一条路点都没有？**
状态「就绪」表示 Xaero 数据链路正常，但当前标记组的路点**全部被过滤器排除了**。最常见原因：标记组里只有**临时导航点**（用 Xaero 目的地功能产生的点，默认被过滤）。解决：模组设置打开「包含临时标记」，或改 TOML `includeTemporaryWaypoints=true`；死亡点同理开「包含死亡点」。按 B 键新建标点默认就能显示。

**Q：服务器没装这个模组，能用吗？**
能。标注同步依赖 Forge 网络包，检测到服务端没有模组时同步自动休眠，本地标注照画，进服无感。

**Q：不同模组版本的客户端能互联吗？**
能。网络协议向后兼容，新旧版本客户端可互通。

## 从源码构建

```bash
./gradlew build
# 产物位于 build/libs/
```

依赖经 `fg.deobf` 从 Modrinth 拉取（见 `build.gradle` 与 `gradle.properties`），首次构建需联网。
