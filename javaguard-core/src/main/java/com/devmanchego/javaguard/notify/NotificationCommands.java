package com.devmanchego.javaguard.notify;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The operating-system calls behind the {@code sound} and {@code toast} channels (specification &sect;16), as argument
 * lists: never a shell string. Text for the user travels in environment variables, not in the script, so a title or a
 * message can contain any character without being interpreted.
 */
public final class NotificationCommands {

    public enum Os {
        WINDOWS, MACOS, LINUX;

        public static Os current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return name.contains("win") ? WINDOWS : name.contains("mac") ? MACOS : LINUX;
        }
    }

    /** A command to run and the variables it needs. */
    public record Call(List<String> command, Map<String, String> env) {
        public Call {
            command = List.copyOf(command);
            env = Map.copyOf(env);
        }
    }

    static final String TITLE_VAR = "JAVAGUARD_NOTIFY_TITLE";
    static final String BODY_VAR = "JAVAGUARD_NOTIFY_BODY";

    private static final String WINDOWS_TOAST = String.join("; ",
            "$ErrorActionPreference='Stop'",
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null",
            "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null",
            "$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)",
            "$nodes = $xml.GetElementsByTagName('text')",
            "$nodes.Item(0).AppendChild($xml.CreateTextNode($env:" + TITLE_VAR + ")) | Out-Null",
            "$nodes.Item(1).AppendChild($xml.CreateTextNode($env:" + BODY_VAR + ")) | Out-Null",
            "$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)",
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('JavaGuard').Show($toast)");

    private NotificationCommands() {
    }

    public static Optional<Call> sound(Os os, Predicate<String> onPath) {
        return switch (os) {
            case WINDOWS -> Optional.of(new Call(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "[System.Media.SystemSounds]::Exclamation.Play()"), Map.of()));
            case MACOS -> Optional.of(new Call(List.of("afplay", "/System/Library/Sounds/Funk.aiff"), Map.of()));
            case LINUX -> onPath.test("paplay")
                    ? Optional.of(new Call(List.of("paplay", "/usr/share/sounds/freedesktop/stereo/dialog-warning.oga"), Map.of()))
                    : onPath.test("canberra-gtk-play")
                            ? Optional.of(new Call(List.of("canberra-gtk-play", "-i", "dialog-warning"), Map.of()))
                            : Optional.empty();
        };
    }

    public static Optional<Call> toast(Os os, Predicate<String> onPath, String title, String message) {
        Map<String, String> text = Map.of(TITLE_VAR, title, BODY_VAR, message);
        return switch (os) {
            case WINDOWS -> Optional.of(new Call(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_TOAST), text));
            case MACOS -> Optional.of(new Call(List.of("osascript", "-e", "display notification (system attribute \"" + BODY_VAR
                    + "\") with title (system attribute \"" + TITLE_VAR + "\")"), text));
            case LINUX -> onPath.test("notify-send")
                    ? Optional.of(new Call(List.of("notify-send", "--", title, message), Map.of()))
                    : Optional.empty();
        };
    }
}
