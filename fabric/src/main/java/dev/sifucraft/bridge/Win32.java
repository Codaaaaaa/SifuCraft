package dev.sifucraft.bridge;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/** The few kernel32 calls the bridge needs, bound through the Foreign Function & Memory API. */
final class Win32 {
    private Win32() {}

    static final int PAGE_READWRITE = 0x04;
    static final int FILE_MAP_ALL_ACCESS = 0xF001F;
    static final int ERROR_ALREADY_EXISTS = 183;
    static final MemorySegment INVALID_HANDLE_VALUE = MemorySegment.ofAddress(-1L);

    record Result(MemorySegment value, int lastError) {
        boolean isNull() {
            return value.address() == 0;
        }
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR =
            CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

    private static final MethodHandle CREATE_FILE_MAPPING = downcall("CreateFileMappingW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), true);
    private static final MethodHandle MAP_VIEW_OF_FILE = downcall("MapViewOfFile",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG), true);
    private static final MethodHandle UNMAP_VIEW_OF_FILE =
            downcall("UnmapViewOfFile", FunctionDescriptor.of(JAVA_INT, ADDRESS), false);
    private static final MethodHandle CLOSE_HANDLE =
            downcall("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS), false);
    private static final MethodHandle TICK_COUNT = downcall("GetTickCount64", FunctionDescriptor.of(JAVA_LONG), false);

    static long tickCount64() {
        try { return (long)TICK_COUNT.invokeExact(); }
        catch (Throwable t) { throw new IllegalStateException("GetTickCount64 failed", t); }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor, boolean captureLastError) {
        MemorySegment symbol = KERNEL32.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return captureLastError
                ? LINKER.downcallHandle(symbol, descriptor, Linker.Option.captureCallState("GetLastError"))
                : LINKER.downcallHandle(symbol, descriptor);
    }

    /** Creates the named pagefile-backed section, or opens it if it already exists. */
    static Result createFileMapping(String name, int size) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            MemorySegment wideName = arena.allocateFrom(name, StandardCharsets.UTF_16LE);
            MemorySegment handle = (MemorySegment) CREATE_FILE_MAPPING.invokeExact(
                    state, INVALID_HANDLE_VALUE, MemorySegment.NULL, PAGE_READWRITE, 0, size, wideName);
            return new Result(handle, (int) LAST_ERROR.get(state, 0L));
        } catch (Throwable t) {
            throw new IllegalStateException("CreateFileMappingW failed", t);
        }
    }

    static Result mapViewOfFile(MemorySegment mapping, long size) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            MemorySegment view = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(
                    state, mapping, FILE_MAP_ALL_ACCESS, 0, 0, size);
            return new Result(view, (int) LAST_ERROR.get(state, 0L));
        } catch (Throwable t) {
            throw new IllegalStateException("MapViewOfFile failed", t);
        }
    }

    static void unmapViewOfFile(MemorySegment view) {
        try {
            int ignored = (int) UNMAP_VIEW_OF_FILE.invokeExact(view);
        } catch (Throwable t) {
            throw new IllegalStateException("UnmapViewOfFile failed", t);
        }
    }

    static void closeHandle(MemorySegment handle) {
        try {
            int ignored = (int) CLOSE_HANDLE.invokeExact(handle);
        } catch (Throwable t) {
            throw new IllegalStateException("CloseHandle failed", t);
        }
    }
}
