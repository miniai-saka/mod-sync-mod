package io.github.miniai_saka.modsync.client;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.util.*;

/** ProcessHandle.Info.arguments is unavailable on some Windows JDKs. No shell, WMI, or argument file is used. */
final class WindowsArguments {
    interface Kernel extends StdCallLibrary {
        Pointer GetCommandLineW();
        Pointer LocalFree(Pointer memory);
    }
    interface Shell extends StdCallLibrary {
        Pointer CommandLineToArgvW(WString commandLine, IntByReference count);
    }
    private WindowsArguments() {}
    static List<String> current() throws IOException {
        try {
            Kernel kernel = Native.load("kernel32", Kernel.class);
            Shell shell = Native.load("shell32", Shell.class);
            Pointer command = kernel.GetCommandLineW();
            if (command == null) throw new IOException("Windows command line is unavailable");
            IntByReference count = new IntByReference();
            Pointer args = shell.CommandLineToArgvW(new WString(command.getWideString(0)), count);
            if (args == null || count.getValue() < 2) throw new IOException("Windows could not parse the Java command line");
            try {
                List<String> result = new ArrayList<>();
                for (int i = 0; i < count.getValue(); i++) result.add(args.getPointer((long) i * Native.POINTER_SIZE).getWideString(0));
                return result;
            } finally { kernel.LocalFree(args); }
        } catch (LinkageError | RuntimeException e) { throw new IOException("Windows起動引数を取得できませんでした。", e); }
    }
}
