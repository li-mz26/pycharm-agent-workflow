package com.limz26.workflow.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(
    name = "AgentWorkflowSettings",
    storages = [Storage("AgentWorkflowSettings.xml")]
)
class AppSettings : PersistentStateComponent<AppSettings.State> {
    data class State(
        var apiKey: String = "",
        var apiEndpoint: String = "https://api.openai.com/v1",
        var model: String = "gpt-4",
        var temperature: Double = 0.7,
        var workflowPath: String = "",  // 工作流文件夹路径，空则使用项目根目录
        var autoDetectWorkflows: Boolean = true,  // 自动检测工作流文件夹
        var mcpServerEnabled: Boolean = true,
        var mcpServerPort: Int = 8765,
        var pythonPath: String = "python3"  // Python 解释器路径
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var apiKey: String
        get() = state.apiKey
        set(value) { state.apiKey = value }
        
    var apiEndpoint: String
        get() = state.apiEndpoint
        set(value) { state.apiEndpoint = value }
        
    var model: String
        get() = state.model
        set(value) { state.model = value }
        
    var temperature: Double
        get() = state.temperature
        set(value) { state.temperature = value }
        
    var workflowPath: String
        get() = state.workflowPath
        set(value) { state.workflowPath = value }
        
    var autoDetectWorkflows: Boolean
        get() = state.autoDetectWorkflows
        set(value) { state.autoDetectWorkflows = value }

    var mcpServerEnabled: Boolean
        get() = state.mcpServerEnabled
        set(value) { state.mcpServerEnabled = value }

    var mcpServerPort: Int
        get() = state.mcpServerPort
        set(value) { state.mcpServerPort = value }

    var pythonPath: String
        get() = state.pythonPath
        set(value) { state.pythonPath = value }
    
    /**
     * 获取实际的工作流路径
     * @param projectBasePath 项目根目录
     * @return 工作流文件夹路径
     */
    fun getWorkflowPath(projectBasePath: String): String {
        return if (workflowPath.isNotEmpty()) {
            workflowPath
        } else {
            projectBasePath
        }
    }
}
