package ru.lct.heat.geometry;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImportCacheTest {
    @Test void keepsOnlyTheMostRecentlyUsedImports(){
        ImportCache<Integer> cache=new ImportCache<>(2,Long.MAX_VALUE,v->1);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();AtomicInteger loads=new AtomicInteger();
        cache.get(a,k->loads.incrementAndGet());cache.get(b,k->loads.incrementAndGet());cache.get(a,k->loads.incrementAndGet());  // a is now the newest
        cache.get(c,k->loads.incrementAndGet());                                                                                  // evicts b
        assertEquals(2,cache.size());assertEquals(3,loads.get());
        cache.get(a,k->loads.incrementAndGet());assertEquals(3,loads.get(),"a stayed cached");
        cache.get(b,k->loads.incrementAndGet());assertEquals(4,loads.get(),"b was evicted and is reloaded");
    }
    @Test void boundsTheTotalWeightButNeverEvictsTheEntryJustRequested(){
        ImportCache<Long> cache=new ImportCache<>(10,100,v->v);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),huge=UUID.randomUUID();
        cache.get(a,k->60L);cache.get(b,k->30L);assertEquals(2,cache.size());
        cache.get(huge,k->500L);                       // alone larger than the budget: still served
        assertEquals(1,cache.size());assertEquals(500,cache.totalWeight());
        assertEquals(500L,cache.get(huge,k->{throw new AssertionError("must be cached");}));
    }
    @Test void evictForgetsAnImport(){
        ImportCache<Integer> cache=new ImportCache<>(3,Long.MAX_VALUE,v->1);UUID a=UUID.randomUUID();AtomicInteger loads=new AtomicInteger();
        cache.get(a,k->loads.incrementAndGet());cache.evict(a);cache.get(a,k->loads.incrementAndGet());assertEquals(2,loads.get());
    }
    @Test void theDefaultBudgetScalesWithTheHeap(){assertTrue(ImportCache.defaultPointBudget()>=500_000L);}
}
