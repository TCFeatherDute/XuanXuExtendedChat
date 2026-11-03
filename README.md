# 玄墟聊天 (XuanXu Chat)

以聊天 App 风格重铸 Minecraft 原版聊天界面。

按 `T` 打开的不再是原版输入框，而是一个完整的聊天 App 界面：左侧会话列表、右侧消息气泡流、底部输入框。

## 功能

### 界面

- **消息气泡 + 玩家头像**：每条消息显示发送者皮肤头像（头部纹理），自己的消息靠右、他人靠左
- **会话侧边栏**：公共频道 + 在线玩家列表，点击玩家进入私聊
- **深色 / 浅色主题**：侧边栏底部按钮切换
- **消息滚动**：鼠标滚轮翻阅历史消息

### 交互

- **表情 / 颜文字面板**：输入框右侧 `☺` 按钮，20 个 emoji + 6 个颜文字，点击插入
- **私聊**：点击侧边栏玩家或消息头像即可切换到私聊，发送时自动走 `/msg`
- **右键头像菜单**：可扩展的动作菜单（默认「复制玩家名」）
- **聊天历史持久化**：按存档 / 服务器保存到 `run/xuanxu/chat/`，重新进入自动恢复（每会话最多 200 条）
- **防重复**：本地发送立即显示 + 服务端回显自动去重

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
XuanXuChat/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── src/main/
    ├── java/com/tc/xuanxuchat/
    │   ├── XuanXuChatMod.java        主类
    │   ├── ChatHub.java              消息中枢（接收 / 分组 / 去重）
    │   ├── ChatMessage.java          消息数据模型
    │   ├── ChatHistoryStore.java     聊天历史持久化
    │   ├── ChatTheme.java            深色 / 浅色主题
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
