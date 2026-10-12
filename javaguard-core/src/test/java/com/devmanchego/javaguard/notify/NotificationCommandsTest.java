package com.devmanchego.javaguard.notify;

import com.devmanchego.javaguard.notify.NotificationCommands.Call;
import com.devmanchego.javaguard.notify.NotificationCommands.Os;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 15: the command lines of the sound and toast channels, per operating system. */
class NotificationCommandsTest {

    private static final String HOSTILE_TITLE = "halted \"x\"; Remove-Item -Recurse C:\\ #";
    private static final String HOSTILE_BODY = "it's $(rm -rf /) `calc` & <b>";

    @Test
    void windowsSoundIsTheExclamationSystemSound() {
        Call call = NotificationCommands.sound(Os.WINDOWS, name -> false).orElseThrow();

        assertThat(call.command()).startsWith("powershell", "-NoProfile").last().asString()
                .isEqualTo("[System.Media.SystemSounds]::Exclamation.Play()");
    }

    @Test
    void macSoundIsFunkAndLinuxPrefersPaplayThenCanberra() {
        assertThat(NotificationCommands.sound(Os.MACOS, name -> false).orElseThrow().command())
                .containsExactly("afplay", "/System/Library/Sounds/Funk.aiff");
        assertThat(NotificationCommands.sound(Os.LINUX, Set.of("paplay", "canberra-gtk-play")::contains).orElseThrow().command().get(0))
                .isEqualTo("paplay");
        assertThat(NotificationCommands.sound(Os.LINUX, Set.of("canberra-gtk-play")::contains).orElseThrow().command().get(0))
                .isEqualTo("canberra-gtk-play");
        assertThat(NotificationCommands.sound(Os.LINUX, name -> false)).isEmpty();
    }

    @Test
    void windowsToastUsesTheWinRtApiAndReceivesTheTextThroughTheEnvironment() {
        Call call = NotificationCommands.toast(Os.WINDOWS, name -> false, HOSTILE_TITLE, HOSTILE_BODY).orElseThrow();

        String script = call.command().get(call.command().size() - 1);
        assertThat(script).contains("Windows.UI.Notifications.ToastNotificationManager").contains("$env:JAVAGUARD_NOTIFY_TITLE")
                .contains("$env:JAVAGUARD_NOTIFY_BODY");
        assertThat(call.command()).noneMatch(arg -> arg.contains("Remove-Item") || arg.contains("calc"));
        assertThat(call.env()).containsEntry("JAVAGUARD_NOTIFY_TITLE", HOSTILE_TITLE).containsEntry("JAVAGUARD_NOTIFY_BODY", HOSTILE_BODY);
    }

    @Test
    void macToastReadsTheTextFromTheEnvironmentToo() {
        Call call = NotificationCommands.toast(Os.MACOS, name -> false, HOSTILE_TITLE, HOSTILE_BODY).orElseThrow();

        assertThat(call.command()).startsWith("osascript", "-e");
        assertThat(call.command().get(2)).contains("system attribute").doesNotContain("rm -rf");
        assertThat(call.env()).containsEntry("JAVAGUARD_NOTIFY_BODY", HOSTILE_BODY);
    }

    @Test
    void linuxToastIsNotifySendWithTheTextAsPlainArgumentsAndOnlyWhenInstalled() {
        Call call = NotificationCommands.toast(Os.LINUX, Set.of("notify-send")::contains, "Title", HOSTILE_BODY).orElseThrow();

        assertThat(call.command()).containsExactly("notify-send", "--", "Title", HOSTILE_BODY);
        assertThat(NotificationCommands.toast(Os.LINUX, name -> false, "Title", "Body")).isEmpty();
    }
}
