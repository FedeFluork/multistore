package com.multistore.core.installer

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.InstallerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Choosing the installer.
 *
 * This class used not to have a single test, and the reason was half good: with only one installer
 * registered there was nothing to choose. Now there are three, and the two questions the selector
 * answers — "who installs?" and "who installs **without asking anything**?" — have different
 * outcomes, not merely different values.
 */
class InstallerSelectorTest {

    @Test
    fun `with no preference the first available in the chain wins`() = runTest {
        val selector = selector(session(), shizuku(), root())

        assertThat(selector.select().kind).isEqualTo(InstallerKind.ROOT)
    }

    @Test
    fun `a switched-off channel is not considered`() = runTest {
        val selector = selector(session(), shizuku(), root(available = false))

        assertThat(selector.select().kind).isEqualTo(InstallerKind.SHIZUKU)
    }

    @Test
    fun `with SessionInstaller alone that is where we end up`() = runTest {
        assertThat(selector(session()).select().kind).isEqualTo(InstallerKind.SESSION)
    }

    @Test
    fun `preferring is not demanding`() = runTest {
        val selector = selector(session(), shizuku(available = false), root(available = false))

        // A preference that cannot be honoured does not make the installation fail: we descend the
        // chain. It is the promise that no feature requires Shizuku or root.
        assertThat(selector.select(InstallerKind.SHIZUKU).kind).isEqualTo(InstallerKind.SESSION)
    }

    @Test
    fun `the preference beats the chain's order`() = runTest {
        val selector = selector(session(), shizuku(), root())

        assertThat(selector.select(InstallerKind.SHIZUKU).kind).isEqualTo(InstallerKind.SHIZUKU)
    }

    @Test
    fun `with no installer registered it breaks, instead of pretending`() = runTest {
        val error = runCatching { selector().select() }.exceptionOrNull()

        // It is not a condition the user can cause: it is Hilt's graph being wrong, and it has to
        // surface immediately instead of becoming an installation that never starts.
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `with no silent channels the silent request has no answer`() = runTest {
        val selector = selector(session(), shizuku(available = false), root(available = false))

        // `null`, not `SessionInstaller`. Whoever asks for a silent installation has nobody to show
        // the system confirmation to: degrading would mean an open session and a user who saw
        // nothing.
        assertThat(selector.selectSilent()).isNull()
    }

    @Test
    fun `the silent request takes the available silent channel`() = runTest {
        val selector = selector(session(), shizuku(), root(available = false))

        assertThat(selector.selectSilent()?.kind).isEqualTo(InstallerKind.SHIZUKU)
    }

    @Test
    fun `whoever chose the system confirmation does not have it skipped`() = runTest {
        val selector = selector(session(), shizuku(), root())

        // Shizuku is there and ready, but the user asked to see the confirmation screen: installing
        // silently on their behalf, taking advantage of nobody watching at that moment, is exactly
        // what they did not ask for.
        assertThat(selector.selectSilent(InstallerKind.SESSION)).isNull()
    }

    @Test
    fun `a switched-off silent preference falls to the other silent channel`() = runTest {
        val selector = selector(session(), shizuku(available = false), root())

        // Here the preference is about the *how*, and stays satisfied: the user asked not to be
        // asked anything.
        assertThat(selector.selectSilent(InstallerKind.SHIZUKU)?.kind).isEqualTo(InstallerKind.ROOT)
    }

    @Test
    fun `availability tells 'it exists' from 'it works now'`() = runTest {
        val selector = selector(session(), shizuku(), root(available = false))

        val availability = selector.availability()

        assertThat(availability.supported)
            .containsExactly(InstallerKind.SESSION, InstallerKind.SHIZUKU, InstallerKind.ROOT)
        assertThat(availability.usable).containsExactly(InstallerKind.SESSION, InstallerKind.SHIZUKU)
        assertThat(availability.silent).containsExactly(InstallerKind.SHIZUKU)
        assertThat(availability.hasSilent).isTrue()
    }

    @Test
    fun `with no silent channels availability says so`() = runTest {
        val availability = selector(session()).availability()

        // It is the line that switches off "install updates by itself" in Settings: without it, it
        // would stay an switch that does nothing.
        assertThat(availability.hasSilent).isFalse()
    }

    @Test
    fun `the permission request reaches the right installer, and no other`() = runTest {
        val shizuku = shizuku(available = false)
        val root = root(available = false)
        val selector = selector(session(), shizuku, root)

        assertThat(selector.requestPermission(InstallerKind.SHIZUKU)).isTrue()

        assertThat(shizuku.permissionRequests).isEqualTo(1)
        assertThat(root.permissionRequests).isEqualTo(0)
    }

    @Test
    fun `asking permission of an installer that does not exist answers no`() = runTest {
        assertThat(selector(session()).requestPermission(InstallerKind.ROOT)).isFalse()
    }

    private fun selector(vararg installers: Installer) = InstallerSelector(installers.toSet())

    private fun session() = FakeInstaller(InstallerKind.SESSION, silent = false, available = true)

    private fun shizuku(available: Boolean = true) =
        FakeInstaller(InstallerKind.SHIZUKU, silent = true, available = available)

    private fun root(available: Boolean = true) =
        FakeInstaller(InstallerKind.ROOT, silent = true, available = available)

    private class FakeInstaller(
        override val kind: InstallerKind,
        silent: Boolean,
        private val available: Boolean,
    ) : Installer {

        override val supportsSilent: Boolean = silent

        var permissionRequests = 0
            private set

        override suspend fun isAvailable(): Boolean = available

        override suspend fun requestPermission(): Boolean {
            permissionRequests++
            return true
        }

        override fun install(request: InstallRequest): Flow<InstallProgress> = emptyFlow()

        override fun uninstall(packageName: String): Flow<UninstallProgress> = emptyFlow()
    }
}
