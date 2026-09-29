package ru.lct.heat.network;

import java.util.List;

public class NetworkRequest {
    public List<Long> targetOrdinals;
    /** Внутренний второй проход: цели, для которых обычный поиск не дал результата, получают широкий поиск (см. RouteService.wideSearch). */
    @com.fasterxml.jackson.annotation.JsonIgnore public boolean wide;
    /** Внутреннее: цели, которые получают широкий поиск в этом проходе (null = все); ограничено, см. NetworkService.MAX_WIDE_TARGETS. */
    @com.fasterxml.jackson.annotation.JsonIgnore public java.util.Set<Long> wideOnly;
    /** Внутренний третий проход: цель, всё ещё не решённая после широкого поиска, получает ещё одну, ещё более широкую попытку
     *  (см. NetworkService.MAX_SUPER_WIDE_TARGETS и RouteService.SUPER_WIDE_*). Подразумевает wide. */
    @com.fasterxml.jackson.annotation.JsonIgnore public boolean superWide;
}
