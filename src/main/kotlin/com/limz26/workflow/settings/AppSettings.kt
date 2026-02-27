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
        var temperature: Double = 0.7
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
}
