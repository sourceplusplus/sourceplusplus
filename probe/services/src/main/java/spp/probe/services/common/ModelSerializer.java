package spp.probe.services.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import spp.probe.services.common.serialize.RuntimeClassIdentityTypeAdapterFactory;
import spp.probe.services.common.serialize.RuntimeClassNameTypeAdapterFactory;
import spp.probe.services.common.serialize.SizeCappedTypeAdapterFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

public enum ModelSerializer {
    INSTANCE;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private final static Set<String> ignoredTypes = new HashSet<>();

    static {
        ignoredTypes.add("org.apache.skywalking.apm.plugin.spring.mvc.commons.EnhanceRequireObjectCache");
    }

    public final Gson extendedGson = new GsonBuilder()
            .registerTypeAdapterFactory(new SizeCappedTypeAdapterFactory())
            .registerTypeAdapterFactory(new TypeAdapterFactory() {

                @Override
                @SuppressWarnings("unchecked")
                public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
                    if (ignoredTypes.contains(typeToken.getRawType().getName())) {
                        return new TypeAdapter<T>() {

                            @Override
                            public void write(JsonWriter jsonWriter, T ignored) {
                            }

                            @Override
                            public T read(JsonReader jsonReader) {
                                return null;
                            }
                        };
                    }
                    return null;
                }
            })
            .registerTypeAdapterFactory(new TypeAdapterFactory() {

                @Override
                @SuppressWarnings("unchecked")
                public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
                    if (OutputStream.class.isAssignableFrom(typeToken.getRawType())) {
                        return (TypeAdapter<T>) new TypeAdapter<OutputStream>() {

                            @Override
                            public void write(JsonWriter jsonWriter, OutputStream outputStream) throws IOException {
                                jsonWriter.beginObject();
                                jsonWriter.endObject();
                            }

                            @Override
                            public OutputStream read(JsonReader jsonReader) {
                                return null;
                            }
                        };
                    }
                    return null;
                }
            })
            .registerTypeAdapterFactory(RuntimeClassIdentityTypeAdapterFactory.of(Object.class))
            .registerTypeAdapterFactory(RuntimeClassNameTypeAdapterFactory.of(Object.class))
            .disableHtmlEscaping().create();

    public String toJson(Object src) {
        return gson.toJson(src);
    }

    public String toExtendedJson(Object src) {
        return extendedGson.toJson(src);
    }
}
