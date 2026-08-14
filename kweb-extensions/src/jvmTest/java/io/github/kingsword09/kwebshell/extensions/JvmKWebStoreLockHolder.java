package io.github.kingsword09.kwebshell.extensions;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JvmKWebStoreLockHolder {
    private JvmKWebStoreLockHolder() {}

    public static void main(String[] args) throws Exception {
        Path lockPath = Path.of(args[0]);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }
}
