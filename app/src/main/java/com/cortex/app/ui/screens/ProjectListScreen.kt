package com.cortex.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortex.app.data.repository.CortexRepository
import com.cortex.app.monetization.RevenueCatManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    repository: CortexRepository,
    onProjectSelected: () -> Unit,
    onBack: () -> Unit,
    onOpenPaywall: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val projects by repository.projects.collectAsState()
    val selectedProject by repository.selectedProject.collectAsState()
    val isPro by RevenueCatManager.isProUser.collectAsState()

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectTitle by remember { mutableStateOf("") }
    var newProjectDesc by remember { mutableStateOf("") }
    val colorOptions = listOf("#4F46E5", "#10B981", "#F59E0B", "#EC4899", "#06B6D4")
    var selectedColorHex by remember { mutableStateOf(colorOptions.first()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Courses & Projects", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (projects.size >= 2 && !isPro) {
                        onOpenPaywall()
                    } else {
                        showNewProjectDialog = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (projects.size >= 2 && !isPro) Icons.Default.Star else Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = {
                    Text(if (projects.size >= 2 && !isPro) "Unlock Unlimited Courses" else "New Course")
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(projects, key = { it.id }) { project ->
                val isCurrent = project.id == selectedProject?.id
                val badgeColor = remember(project.colorHex) {
                    try {
                        Color(android.graphics.Color.parseColor(project.colorHex))
                    } catch (_: Exception) {
                        Color(0xFF6366F1)
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            repository.selectProject(project)
                            onProjectSelected()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.School,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = project.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                if (project.description.isNotBlank()) {
                                    Text(
                                        text = project.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }

                        if (isCurrent) {
                            Surface(
                                color = badgeColor,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Active",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showNewProjectDialog) {
            AlertDialog(
                onDismissRequest = { showNewProjectDialog = false },
                title = { Text("Create Course Project") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = newProjectTitle,
                            onValueChange = { newProjectTitle = it },
                            label = { Text("Course Title (e.g. Calculus II)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newProjectDesc,
                            onValueChange = { newProjectDesc = it },
                            label = { Text("Description or Professor") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Course Color Theme",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            colorOptions.forEach { hex ->
                                val c = Color(android.graphics.Color.parseColor(hex))
                                val isChosen = hex == selectedColorHex
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            width = if (isChosen) 2.5.dp else 0.dp,
                                            color = if (isChosen) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColorHex = hex }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newProjectTitle.isNotBlank()) {
                                repository.addProject(
                                    title = newProjectTitle.trim(),
                                    description = newProjectDesc.trim(),
                                    colorHex = selectedColorHex
                                )
                                newProjectTitle = ""
                                newProjectDesc = ""
                                showNewProjectDialog = false
                                onProjectSelected()
                            }
                        }
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewProjectDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
