package ru.lct.heat.geometry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Структуры в памяти на один импорт (индекс препятствий, индекс сети) большие. Здесь хранятся только недавно
 * использованные импорты, ограниченные и по числу, и по суммарному весу (хранимые координаты), чтобы долго работающий
 * сервис, повидавший много больших импортов, не исчерпал heap. Только что запрошенная запись никогда не вытесняется,
 * поэтому один импорт, превышающий бюджет, всё равно обслуживается. Попадание в кэш не требует блокировки: каждая
 * проверка ограничений каждого параллельного поиска обращается к индексу своего импорта, поэтому обращения не должны
 * сериализовать воркеры.
 */
public final class ImportCache<V> {
    private static final class Entry<V> {
        final V value;final long weight;volatile long used;
        Entry(V value,long weight,long used){this.value=value;this.weight=weight;this.used=used;}
    }
    private final ConcurrentHashMap<UUID,Entry<V>> entries=new ConcurrentHashMap<>();
    private final AtomicLong clock=new AtomicLong();
    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<V> weigher;

    public ImportCache(int maxEntries,long maxWeight,ToLongFunction<V> weigher){this.maxEntries=Math.max(1,maxEntries);this.maxWeight=maxWeight;this.weigher=weigher;}

    /** Бюджет координат на один кэш: примерно четверть heap при ~64 байтах на хранимую координату. */
    public static long defaultPointBudget(){return Math.max(500_000L,Runtime.getRuntime().maxMemory()/4/64);}
    // Минимум один слот на каждый одновременно считающийся импорт (CostService.CONCURRENT_CALCULATIONS), плюс небольшой
    // запас, чтобы индекс только что завершённого импорта не вытеснялся в тот же момент, когда начинается новый.
    public static int defaultEntries(){return Integer.getInteger("lct.cache.imports",6);}

    public V get(UUID id,Function<UUID,V> loader){
        Entry<V> hit=entries.get(id);
        if(hit!=null){hit.used=clock.incrementAndGet();return hit.value;}
        synchronized(this){
            hit=entries.get(id);
            if(hit!=null){hit.used=clock.incrementAndGet();return hit.value;}
            V value=loader.apply(id);
            entries.put(id,new Entry<>(value,weigher.applyAsLong(value),clock.incrementAndGet()));
            trim(id);
            return value;
        }
    }
    public synchronized void evict(UUID id){entries.remove(id);}
    public int size(){return entries.size();}
    public long totalWeight(){long t=0;for(Entry<V> e:entries.values())t+=e.weight;return t;}

    /** Вызывается с удержанием блокировки: удаляет наименее давно использованные записи, кроме {@code keep}. */
    private void trim(UUID keep){
        while(entries.size()>1&&(entries.size()>maxEntries||totalWeight()>maxWeight)){
            UUID eldest=null;long oldest=Long.MAX_VALUE;
            for(Map.Entry<UUID,Entry<V>> e:entries.entrySet())if(!e.getKey().equals(keep)&&e.getValue().used<oldest){oldest=e.getValue().used;eldest=e.getKey();}
            if(eldest==null)return;
            entries.remove(eldest);
        }
    }
}
