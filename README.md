# XuanXu Extended — TC's Multiplayer Chat Revolution

> 玄墟扩展 - TC的多人聊天革新

以聊天 App 风格重铸 Minecraft 原版聊天界面。

按 `T` 打开的不再是原版输入框，而是一个完整的聊天 App 界面：左侧会话列表、右侧消息气泡流、底部输入框。

## 功能

### 界面

- **消息气泡 + 玩家头像**：每条消息显示发送者皮肤头像（头部纹理），自己的消息靠右、他人靠左
- **会话侧边栏**：公共频道 + 在线玩家列表，点击玩家进入私聊
- **收起右侧面板**：标题栏 `×` 把聊天区整个收起来，只剩会话列表；点任意会话重新展开（折叠前没发出去的草稿会自动恢复）
- **搜索消息**：侧边栏顶部搜索框实时过滤，回车把焦点交回聊天输入框
- **消息滚动**：鼠标滚轮翻阅历史消息；向上翻看时收到新消息不会被拽回底部
- **时间分隔**：消息间隔超过 5 分钟时插入时间分隔线（如「14:30」／跨天显示「11-04 14:30」）
- **系统头像**：系统消息（公告、提示）显示独立的灰色系统头像，居中展示
- **未读红点**：不在当前会话时收到新消息，侧边栏对应条目显示红色未读数（收起右侧面板时同样累计）
- **图片 / GIF 消息**：消息中包含图片链接（png / jpg / jpeg / gif / webp）时自动下载并显示缩略图，GIF 支持逐帧动画

### 交互

- **表情面板**：输入框右侧 `☺` 按钮，内置 8 个黄豆表情 + 6 个颜文字；把 PNG 丢进 `xuanxuchat/emojis/` 还能加上自定义表情，三组平铺在一起
- **文本 + 表情混排**：`你好😊`、`你好:smile:` 这类消息会在气泡里把表情当小图渲染，和文字排在同一行
- **命令**：输入 `/` 开头的文本走命令通道（和原版行为一致）执行；执行后公共频道会补一条**只有自己可见**的命令记录
- **私聊**：点击侧边栏玩家或消息头像即可切换到私聊，发送时自动走 `/msg`
- **右键头像菜单**：可扩展的动作菜单（默认「复制玩家名」）
- **右键消息菜单**：复制消息 / 引用回复（引用会以 `> 原消息` 前缀填入输入框）
- **@ 提及补全**：输入 `@` 自动弹出在线玩家列表，点击插入
- **防刷屏**：连续重复消息自动合并为一条并计数（显示 `xN`）
- **聊天历史持久化**：按存档 / 服务器保存到 `run/xuanxu/chat/`，重新进入自动恢复（每会话最多 200 条）
- **私聊回显去重**：识别服务端「悄悄话」回显格式，不会重复显示

## 扩展接口

右键玩家头像的菜单是可扩展的——**任何 mod 都可以注册自己的菜单项**：

```java
PlayerHeadActions.register(new PlayerHeadAction() {
    @Override
    public String label() {
        return "查看灵根";      // 菜单项文字
    }

    @Override
    public boolean isVisible(String playerName) {
        return true;            // 对哪些玩家显示
    }

    @Override
    public void execute(String playerName) {
        // 点击后执行，例如打开该玩家的属性面板
    }
});
```

注册后，右键任意消息气泡上的玩家头像即可看到对应菜单项。

## 构建

环境要求：

- **JDK 21**
- 可访问 Maven（下载 NeoForge / Minecraft 依赖）

```bash
# 启动客户端测试
./gradlew runClient

# 构建 jar（产物在 build/libs/）
./gradlew build
```

> 国内网络访问 `maven.neoforged.net` 可能需要代理，可在 `gradle.properties` 中配置。

## 版本

- Minecraft：**1.21.1**
- NeoForge：**21.1.234**
- 运行环境：**客户端需装，服务端可选**（服务端未安装时使用原版聊天协议）

## 项目结构

```
XuanXuExtended/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── src/main/
    ├── java/com/tc/xuanxuchat/
    │   ├── XuanXuChatMod.java        主类
    │   ├── ChatHub.java              消息中枢（接收 / 分组 / 去重 / 未读）
    │   ├── ChatMessage.java          消息数据模型
    │   ├── ChatHistoryStore.java     聊天历史持久化
    │   ├── ChatEmojiLoader.java      自定义表情 PNG 加载
    │   ├── ChatImageCache.java       图片 / GIF 下载与缓存
    │   ├── ChatTheme.java            配色（目前只用深色）
    │   ├── PlayerHeadAction.java     右键头像动作接口
    │   ├── PlayerHeadActions.java    右键头像动作注册表
    │   └── client/
    │       ├── ChatEvents.java       事件监听（替换聊天界面 / 收消息）
    │       └── XuanXuChatScreen.java 聊天主界面
    └── resources/
        ├── META-INF/neoforge.mods.toml
        └── pack.mcmeta
```

## 许可证

[MIT](LICENSE)
