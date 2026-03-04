package com.limz26.workflow.mcp

import com.google.gson.GsonBuilder
import com.limz26.workflow.model.*
import com.limz26.workflow.service.WorkflowService
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkflowMcpAlarmWorkflowIntegrationTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `mcp tools can build validate and run complex alarm analysis workflow`() {
        val service = WorkflowMcpService()
        service.setWorkflowService(WorkflowService())
        val projectDir = Files.createTempDirectory("mcp-alarm-workflow-project").toFile()

        // 1) 模拟 MCP: workflow_create
        val created = service.createWorkflow(projectDir.absolutePath, "Server Alarm Analysis")
        val workflowDir = File(created.workflowDirPath)

        val initialDef = gson.fromJson(File(workflowDir, "workflow.json").readText(), WorkflowDefinition::class.java)
        val startId = initialDef.nodes.first { it.type == "start" }.id
        val endId = initialDef.nodes.first { it.type == "end" }.id

        // 2) 模拟 MCP: workflow_add_node
        val parseNode = NodeDefinition(
            id = "code_parse_alert",
            type = "code",
            name = "解析告警",
            position = PositionDefinition(240, 0),
            config = NodeConfigDefinition(codeFile = "nodes/code_parse_alert.py")
        )
        val branchNode = NodeDefinition(
            id = "branch_alert_type",
            type = "branch",
            name = "按告警类型分支",
            position = PositionDefinition(520, 0),
            config = NodeConfigDefinition(
                branchField = "alert_type",
                branchCases = mapOf(
                    "server_cpu" to "code_enrich_server",
                    "network_port" to "code_enrich_network"
                ),
                defaultTarget = "code_enrich_server"
            )
        )
        val enrichServerNode = NodeDefinition(
            id = "code_enrich_server",
            type = "code",
            name = "补全服务器信息",
            position = PositionDefinition(800, -80),
            config = NodeConfigDefinition(codeFile = "nodes/code_enrich_server.py")
        )
        val enrichNetworkNode = NodeDefinition(
            id = "code_enrich_network",
            type = "code",
            name = "补全网络信息",
            position = PositionDefinition(800, 80),
            config = NodeConfigDefinition(codeFile = "nodes/code_enrich_network.py")
        )
        val neighborAlertsNode = NodeDefinition(
            id = "code_neighbor_alerts",
            type = "code",
            name = "查询邻居告警",
            position = PositionDefinition(1080, 0),
            config = NodeConfigDefinition(codeFile = "nodes/code_neighbor_alerts.py")
        )
        val metricsNode = NodeDefinition(
            id = "code_metrics",
            type = "code",
            name = "查询时序指标",
            position = PositionDefinition(1360, 0),
            config = NodeConfigDefinition(codeFile = "nodes/code_metrics.py")
        )
        val classifyNode = NodeDefinition(
            id = "code_classify",
            type = "code",
            name = "告警分类",
            position = PositionDefinition(1640, 0),
            config = NodeConfigDefinition(codeFile = "nodes/code_classify.py")
        )
        val summarizeNode = NodeDefinition(
            id = "code_summary",
            type = "code",
            name = "输出分析",
            position = PositionDefinition(1920, 0),
            config = NodeConfigDefinition(codeFile = "nodes/code_summary.py")
        )

        listOf(
            parseNode,
            branchNode,
            enrichServerNode,
            enrichNetworkNode,
            neighborAlertsNode,
            metricsNode,
            classifyNode,
            summarizeNode
        ).forEach { service.addNode(workflowDir.absolutePath, it) }

        // 3) 模拟 MCP: workflow_delete_edge + workflow_add_edge
        initialDef.edges.forEach { service.deleteEdge(workflowDir.absolutePath, it.id) }
        listOf(
            EdgeDefinition("e_start_parse", startId, parseNode.id),
            EdgeDefinition("e_parse_branch", parseNode.id, branchNode.id),
            EdgeDefinition("e_branch_server", branchNode.id, enrichServerNode.id, "server_cpu"),
            EdgeDefinition("e_branch_network", branchNode.id, enrichNetworkNode.id, "network_port"),
            EdgeDefinition("e_server_neighbor", enrichServerNode.id, neighborAlertsNode.id),
            EdgeDefinition("e_network_neighbor", enrichNetworkNode.id, neighborAlertsNode.id),
            EdgeDefinition("e_neighbor_metrics", neighborAlertsNode.id, metricsNode.id),
            EdgeDefinition("e_metrics_classify", metricsNode.id, classifyNode.id),
            EdgeDefinition("e_classify_summary", classifyNode.id, summarizeNode.id),
            EdgeDefinition("e_summary_end", summarizeNode.id, endId)
        ).forEach { service.addEdge(workflowDir.absolutePath, it) }

        // 4) 模拟 MCP: workflow_write_python_script（含 gateway mock）
        val gatewayPath = File(workflowDir, "nodes/gateway.py")
        service.writePythonScriptFile(
            workflowDir.absolutePath,
            "nodes/gateway.py",
            """
def call(resource, **kwargs):
    if resource == "device.server.info":
        return {
            "device": {"id": kwargs.get("device_id"), "role": "server", "cluster": "prod-a"},
            "topology": {"rack": "R12", "switch": "sw-01"}
        }
    if resource == "device.network.info":
        return {
            "device": {"id": kwargs.get("device_id"), "role": "switch", "site": "dc-1"},
            "topology": {"upstream": "core-01", "segment": "seg-7"}
        }
    if resource == "alerts.neighbors":
        return {
            "neighbors": ["dev-n1", "dev-n2"],
            "neighbor_alerts": [
                {"device_id": "dev-n1", "start": "2024-12-31T10:01:00Z", "end": "2024-12-31T10:20:00Z", "type": "cpu_high"},
                {"device_id": "dev-n2", "start": "2024-12-31T10:03:00Z", "end": "2024-12-31T10:18:00Z", "type": "latency_spike"}
            ]
        }
    if resource == "metrics.timeseries":
        return {
            "cpu": [0.32, 0.41, 0.79, 0.91, 0.88],
            "memory": [0.55, 0.58, 0.62, 0.64, 0.63],
            "network_drop": [0, 1, 5, 9, 3]
        }
    return {}
            """.trimIndent()
        )

        fun script(name: String, body: String) {
            val escapedGateway = gatewayPath.absolutePath.replace("\\", "\\\\")
            val content = """
import importlib.util

_spec = importlib.util.spec_from_file_location("gateway", r"$escapedGateway")
gateway = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gateway)

$body
            """.trimIndent()
            service.writePythonScriptFile(workflowDir.absolutePath, "nodes/$name", content)
        }

        script(
            "code_parse_alert.py",
            """
def main(inputs):
    alert = inputs.get("alert", {})
    return {
        "alert_id": alert.get("id", "A-unknown"),
        "alert_type": alert.get("type", "server_cpu"),
        "device_id": alert.get("device_id", "dev-001"),
        "severity": alert.get("severity", "major"),
        "start_time": alert.get("start_time", "2024-12-31T10:00:00Z"),
        "end_time": alert.get("end_time", "2024-12-31T10:30:00Z"),
        "raw_alert": alert
    }
            """.trimIndent()
        )

        script(
            "code_enrich_server.py",
            """
def main(inputs):
    enrich = gateway.call("device.server.info", device_id=inputs.get("device_id"))
    return {
        **inputs,
        "device_profile": enrich.get("device", {}),
        "topology": enrich.get("topology", {}),
        "enrich_source": "server_api"
    }
            """.trimIndent()
        )

        script(
            "code_enrich_network.py",
            """
def main(inputs):
    enrich = gateway.call("device.network.info", device_id=inputs.get("device_id"))
    return {
        **inputs,
        "device_profile": enrich.get("device", {}),
        "topology": enrich.get("topology", {}),
        "enrich_source": "network_api"
    }
            """.trimIndent()
        )

        script(
            "code_neighbor_alerts.py",
            """
def main(inputs):
    data = gateway.call("alerts.neighbors", device_id=inputs.get("device_id"), around=inputs.get("start_time"))
    return {
        **inputs,
        "neighbor_devices": data.get("neighbors", []),
        "neighbor_alerts": data.get("neighbor_alerts", [])
    }
            """.trimIndent()
        )

        script(
            "code_metrics.py",
            """
def main(inputs):
    metric = gateway.call(
        "metrics.timeseries",
        device_id=inputs.get("device_id"),
        neighbors=inputs.get("neighbor_devices", []),
        start=inputs.get("start_time"),
        end=inputs.get("end_time")
    )
    return {
        **inputs,
        "timeseries": metric
    }
            """.trimIndent()
        )

        script(
            "code_classify.py",
            """
def main(inputs):
    ts = inputs.get("timeseries", {})
    cpu = ts.get("cpu", [])
    drop = ts.get("network_drop", [])
    neighbor_alerts = inputs.get("neighbor_alerts", [])

    cpu_peak = max(cpu) if cpu else 0
    drop_peak = max(drop) if drop else 0

    if cpu_peak > 0.9 and neighbor_alerts:
        category = "resource_cascade"
        reason = "本机CPU尖峰且邻居同时段存在告警"
    elif drop_peak >= 5:
        category = "network_instability"
        reason = "网络丢包指标出现明显峰值"
    else:
        category = "single_point_event"
        reason = "未观察到明显级联特征"

    return {
        **inputs,
        "analysis_category": category,
        "analysis_reason": reason
    }
            """.trimIndent()
        )

        script(
            "code_summary.py",
            """
def main(inputs):
    return {
        "alert_id": inputs.get("alert_id"),
        "alert_type": inputs.get("alert_type"),
        "device_id": inputs.get("device_id"),
        "severity": inputs.get("severity"),
        "category": inputs.get("analysis_category"),
        "reason": inputs.get("analysis_reason"),
        "enrich_source": inputs.get("enrich_source"),
        "neighbor_count": len(inputs.get("neighbor_devices", [])),
        "topology": inputs.get("topology", {}),
        "timeline": {
            "start": inputs.get("start_time"),
            "end": inputs.get("end_time")
        }
    }
            """.trimIndent()
        )

        // 5) 模拟 MCP: workflow_validate + workflow_run
        val validation = service.validateWorkflowJson(null, workflowDir.absolutePath)
        assertTrue("workflow json should be valid", validation.validJson)
        assertTrue("workflow should remain DAG", validation.isDag)

        val runResult = service.runWorkflow(
            workflowDir.absolutePath,
            mapOf(
                "alert" to mapOf(
                    "id" to "ALM-20241231-001",
                    "type" to "network_port",
                    "device_id" to "sw-edge-22",
                    "severity" to "critical",
                    "start_time" to "2024-12-31T10:00:00Z",
                    "end_time" to "2024-12-31T10:30:00Z"
                )
            )
        )

        assertTrue("workflow run should succeed", runResult.success)
        assertTrue(runResult.logs.any { it.contains("分支命中") })
        assertTrue(runResult.logs.any { it.contains("analysis_category") || it.contains("category") })
    }
}
