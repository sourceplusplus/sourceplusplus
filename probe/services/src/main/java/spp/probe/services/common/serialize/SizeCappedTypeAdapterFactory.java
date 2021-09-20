package spp.probe.services.common.serialize;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import spp.probe.services.common.ModelSerializer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;

public class SizeCappedTypeAdapterFactory implements TypeAdapterFactory {

    public static Instrumentation instrumentation;
    public static long maxMemorySize = -1;

    @SuppressWarnings("unused")
    public static void setInstrumentation(Instrumentation instrumentation) {
        SizeCappedTypeAdapterFactory.instrumentation = instrumentation;
    }

    @SuppressWarnings("unused")
    public static void setMaxMemorySize(long maxMemorySize) {
        SizeCappedTypeAdapterFactory.maxMemorySize = maxMemorySize;
    }

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (instrumentation == null || maxMemorySize == -1) return null;
        return new TypeAdapter<T>() {

            @Override
            public void write(JsonWriter jsonWriter, T value) throws IOException {
                long objSize = instrumentation.getObjectSize(value);
                if (objSize <= maxMemorySize) {
                    ModelSerializer.INSTANCE.extendedGson.getDelegateAdapter(SizeCappedTypeAdapterFactory.this, type)
                            .write(jsonWriter, value);
                } else {
                    jsonWriter.beginObject();
                    jsonWriter.name("@class");
                    jsonWriter.value("LargeObject");

                    jsonWriter.name("@size");
                    jsonWriter.value(Long.toString(objSize));

                    jsonWriter.name("@identity");
                    jsonWriter.value(Integer.toHexString(System.identityHashCode(value)));
                    jsonWriter.endObject();
                }
            }

            @Override
            public T read(JsonReader jsonReader) {
                return null;
            }
        };
    }
}
