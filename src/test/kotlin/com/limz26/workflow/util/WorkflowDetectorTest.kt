package com.limz26.workflow.util

import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkflowDetectorTest {
    
    @get:Rule
    val tempFolder = TemporaryFolder()
    
    @Test
    fun `test detect workflow directories`() {
        val root = tempFolder.root
        
        // Create workflow directories
        File(root, "workflows").mkdir()
        File(root, "flows").mkdir()
        File(root, "src").mkdir()
        File(root, "normal").mkdir()
        
        // Create a workflow file in flows
        File(root, "flows/test.json").writeText("{}")
        
        val detected = WorkflowDetector.detectWorkflowDirs(root)
        
        assertTrue("Should detect 'workflows' dir", detected.any { it.endsWith("workflows") })
        assertTrue("Should detect 'flows' dir", detected.any { it.endsWith("flows") })
    }
    
    @Test
    fun `test scan workflow files`() {
        val workflowDir = tempFolder.newFolder("workflows")
        
        // Create workflow files
        File(workflowDir, "test1.json").writeText("{}")
        File(workflowDir, "test2.yaml").writeText("key: value")
        File(workflowDir, "readme.txt").writeText("readme")
        
        val files = WorkflowDetector.scanWorkflowFiles(workflowDir.absolutePath)
        
        assertTrue("Should find at least 2 files", files.size >= 2)
        assertTrue("Should find json file", files.any { it.name == "test1" })
        assertTrue("Should find yaml file", files.any { it.name == "test2" })
    }
    
    @Test
    fun `test is valid workflow file`() {
        val jsonFile = tempFolder.newFile("test.json")
        val txtFile = tempFolder.newFile("test.txt")
        val workflowFile = tempFolder.newFile("my_workflow.json")
        
        assertTrue("JSON should be valid", WorkflowDetector.isValidWorkflowFile(jsonFile))
        assertFalse("TXT should not be valid", WorkflowDetector.isValidWorkflowFile(txtFile))
        assertTrue("Workflow named file should be valid", WorkflowDetector.isValidWorkflowFile(workflowFile))
    }
    
    @Test
    fun `test scan empty directory`() {
        val emptyDir = tempFolder.newFolder("empty")
        val files = WorkflowDetector.scanWorkflowFiles(emptyDir.absolutePath)
        assertTrue("Should return empty list", files.isEmpty())
    }
    
    @Test
    fun `test scan non-existent directory`() {
        val files = WorkflowDetector.scanWorkflowFiles("/non/existent/path")
        assertTrue("Should return empty list for non-existent dir", files.isEmpty())
    }
}
