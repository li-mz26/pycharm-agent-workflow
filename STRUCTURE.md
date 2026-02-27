# 项目结构

```
pycharm-agent-workflow/
├── src/main/kotlin/com/limz26/workflow/
│   ├── actions/
│   │   └── GenerateWorkflowAction.kt      # 右键菜单动作
│   ├── ui/
│   │   ├── WorkflowToolWindowFactory.kt   # 侧边栏窗口
│   │   └── WorkflowPanel.kt               # 主界面面板
│   └── settings/
│       ├── AppSettings.kt                 # 持久化配置
│       └── SettingsConfigurable.kt        # 设置页面
├── src/main/resources/META-INF/
│   └── plugin.xml                         # 插件配置
├── build.gradle.kts                       # Gradle 构建配置
├── settings.gradle.kts
└── README.md
```

## 下一步

1. 实现 LLM 客户端接口
2. 设计工作流 DSL/格式
3. 添加工作流可视化
4. 支持更多 LLM Provider
