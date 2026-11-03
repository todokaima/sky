package com.example.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The lists of HTTP, datasource and Kafka configs, as posted by the side panel forms (configs[i].*). */
public final class Configs {

    private Configs() {}

    public static class Http {
        private List<HttpConfig> configs = new ArrayList<>();

        public List<HttpConfig> getConfigs() { return configs; }
        public void setConfigs(List<HttpConfig> configs) { this.configs = configs; }
    }

    public static class Datasource {
        private List<DatasourceConfig> configs = new ArrayList<>();

        public List<DatasourceConfig> getConfigs() { return configs; }
        public void setConfigs(List<DatasourceConfig> configs) { this.configs = configs; }
    }

    public static class Kafka {
        private List<KafkaConfig> configs = new ArrayList<>();

        public List<KafkaConfig> getConfigs() { return configs; }
        public void setConfigs(List<KafkaConfig> configs) { this.configs = configs; }
    }

    /** The linked config if the index is valid, otherwise the default one (the first if none is marked). */
    public static <T> T pick(List<T> configs, int linked, Predicate<T> isDefault) {
        if (linked >= 0 && linked < configs.size()) {
            return configs.get(linked);
        }
        return configs.stream().filter(isDefault).findFirst().orElse(configs.get(0));
    }

    /** At least one config, and exactly one marked default (the first marked, else the first). */
    public static <T> List<T> normalize(List<T> configs, Supplier<T> fresh, Predicate<T> isDefault, DefaultSetter<T> setDefault) {
        List<T> list = new ArrayList<>(configs);
        if (list.isEmpty()) {
            list.add(fresh.get());
        }
        T chosen = list.stream().filter(isDefault).findFirst().orElse(list.get(0));
        list.forEach(c -> setDefault.set(c, c == chosen));
        return list;
    }

    public interface DefaultSetter<T> {
        void set(T config, boolean isDefault);
    }
}
