package dev.blazelight.p4oc.ui.tabs

import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.domain.session.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

class TabBarTitleTest {
    private val labels = TabTitleLabels(
        fallbackTab = "Tab",
        sessions = "Sessions",
        chat = "Chat",
        files = "Files",
        file = "File",
        terminal = "Terminal",
        settings = "Settings",
        projects = "Projects",
        globalWorkspace = "Global",
        sessionWorkspace = "Session",
    )

    @Test
    fun `sessions route includes global workspace suffix`() {
        assertEquals(
            "Sessions · Global",
            getTitleForRoute("sessions", labels, workspaceKey = WorkspaceKey.Global),
        )
    }

    @Test
    fun `filtered sessions route includes directory workspace suffix`() {
        assertEquals(
            "Sessions · project",
            getTitleForRoute("sessions?project=1", labels, workspaceKey = WorkspaceKey.Directory("/repo/project")),
        )
    }

    @Test
    fun `chat route uses session title with directory workspace suffix`() {
        assertEquals(
            "Investigate bug · project",
            getTitleForRoute(
                route = "chat/session-1",
                labels = labels,
                sessionTitle = "Investigate bug",
                workspaceKey = WorkspaceKey.Directory("/repo/project"),
            ),
        )
    }

    @Test
    fun `chat route falls back to localized chat label`() {
        assertEquals(
            "Chat · Session",
            getTitleForRoute(
                route = "chat/session-1",
                labels = labels,
                workspaceKey = WorkspaceKey.SessionScoped(SessionId("session-1")),
            ),
        )
    }

    @Test
    fun `files route in global workspace uses compact global label`() {
        assertEquals(
            "Global",
            getTitleForRoute("files", labels, workspaceKey = WorkspaceKey.Global),
        )
    }

    @Test
    fun `files path route uses directory basename`() {
        assertEquals(
            "project",
            getTitleForRoute("files/src/Main.kt", labels, workspaceKey = WorkspaceKey.Directory("/repo/project/")),
        )
    }

    @Test
    fun `settings and nested settings routes use settings label`() {
        assertEquals("Settings", getTitleForRoute("settings", labels))
        assertEquals("Settings", getTitleForRoute("settings/about", labels))
    }

    @Test
    fun `projects route uses projects label`() {
        assertEquals("Projects", getTitleForRoute("projects", labels))
    }

    @Test
    fun `unknown and null routes use fallback label`() {
        assertEquals("Tab", getTitleForRoute("diff/1", labels))
        assertEquals("Tab", getTitleForRoute(null, labels))
    }
}
