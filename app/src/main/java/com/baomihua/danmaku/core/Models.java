package com.baomihua.danmaku.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕源返回的作品/分集模型（弹弹play API v2 的形状）。
 */
public final class Models {

    private Models() {
    }

    public static final class Episode {
        public final long episodeId;
        public final long animeId;
        public final String animeTitle;
        public final String title;

        public Episode(long episodeId, long animeId, String animeTitle, String title) {
            this.episodeId = episodeId;
            this.animeId = animeId;
            this.animeTitle = animeTitle == null ? "" : animeTitle;
            this.title = title == null ? "" : title;
        }

        public String display() {
            return title.isEmpty() ? String.valueOf(episodeId) : title;
        }

        @Override
        public String toString() {
            return episodeId + ":" + animeTitle + " / " + title;
        }
    }

    public static final class Anime {
        public final long animeId;
        public final String title;
        public final String typeDescription;
        public final List<Episode> episodes = new ArrayList<>();

        public Anime(long animeId, String title, String typeDescription) {
            this.animeId = animeId;
            this.title = title == null ? "" : title;
            this.typeDescription = typeDescription == null ? "" : typeDescription;
        }

        public String display() {
            if (typeDescription.isEmpty()) {
                return title;
            }
            return title + "  ·  " + typeDescription;
        }

        @Override
        public String toString() {
            return animeId + ":" + title;
        }
    }
}
