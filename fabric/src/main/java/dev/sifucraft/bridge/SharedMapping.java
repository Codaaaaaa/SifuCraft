package dev.sifucraft.bridge;

import java.lang.foreign.MemorySegment;

/**
 * A named, pagefile-backed shared memory section. Either process may create it; the second one
 * opens the existing section. It lives until the last handle to it is closed.
 */
public final class SharedMapping implements AutoCloseable {
    private final MemorySegment handle;
    private final MemorySegment view;
    private final boolean created;
    private boolean closed;

    private SharedMapping(MemorySegment handle, MemorySegment view, boolean created) {
        this.handle = handle;
        this.view = view;
        this.created = created;
    }

    public static SharedMapping open(String name, int size) {
        Win32.Result mapping = Win32.createFileMapping(name, size);
        if (mapping.isNull()) {
            throw new IllegalStateException("CreateFileMappingW(" + name + ") failed, error " + mapping.lastError());
        }
        boolean created = mapping.lastError() != Win32.ERROR_ALREADY_EXISTS;
        Win32.Result view = Win32.mapViewOfFile(mapping.value(), size);
        if (view.isNull()) {
            Win32.closeHandle(mapping.value());
            throw new IllegalStateException("MapViewOfFile(" + name + ") failed, error " + view.lastError());
        }
        return new SharedMapping(mapping.value(), view.value().reinterpret(size), created);
    }

    public MemorySegment segment() {
        return view;
    }

    public boolean created() {
        return created;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Win32.unmapViewOfFile(view);
        Win32.closeHandle(handle);
    }
}
