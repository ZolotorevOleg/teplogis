package ru.lct.heat.routing;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import ru.lct.heat.restrictions.RestrictionEngine;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The in-memory corridor graph must contain the same vertices as the reference database query, on random obstacle fields. */
@SpringBootTest @Testcontainers
class CorridorParityIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;
    @Autowired RouteRepository repository;
    @Autowired RestrictionEngine engine;
    UUID id;long next;

    long add(String objectType,String restrictionType,String wkt,Double flow){
        long ordinal=next++;
        jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?,?)",id,ordinal,"\""+objectType+"-"+ordinal+"\"",objectType);
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,flow_tph,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,?,?,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",
            id,ordinal,objectType.equals("heat_network")?200:null,flow,restrictionType,wkt);
        return ordinal;
    }
    static String box(double x,double y,double w,double h){return "POLYGON(("+x+" "+y+","+(x+w)+" "+y+","+(x+w)+" "+(y+h)+","+x+" "+(y+h)+","+x+" "+y+"))";}

    static Set<String> keys(List<Map<String,Object>> rows){
        Set<String> s=new TreeSet<>();
        for(Map<String,Object> r:rows)s.add(r.get("ordinal")+"|"+Math.round(((Number)r.get("x")).doubleValue()*100)+"|"+Math.round(((Number)r.get("y")).doubleValue()*100));
        return s;
    }

    @Test void theInMemoryCorridorMatchesTheDatabaseQueryOnRandomObstacleFields(){
        Random random=new Random(20260927);
        for(int round=0;round<6;round++){
            id=UUID.randomUUID();next=0;
            jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);
            add("heat_network",null,"LINESTRING(-300 250,300 250)",null);
            String[] kinds={"oks","oks","oks","park","water","railway"};
            for(int i=0;i<25;i++){
                String kind=kinds[random.nextInt(kinds.length)];
                double x=random.nextInt(360)-180,y=random.nextInt(200)-40,w=6+random.nextInt(40),h=6+random.nextInt(30);
                if(Math.abs(x)<8&&Math.abs(y)<8)continue;                              // keep the target's own spot free of other objects
                if(kind.equals("railway"))add("restriction",kind,"LINESTRING("+x+" "+y+","+(x+w)+" "+(y+h)+")",null);
                else add("restriction",kind,box(x,y,w,h),null);
            }
            // the target lies inside its own building
            add("restriction","oks",box(-10,-8,20,16),null);
            long target=add("oks_connection_point",null,"POINT(0 0)",2.0);
            for(int dn:new int[]{50,100,300,600}){
                double width=ru.lct.heat.restrictions.RestrictionRules.width(dn);
                for(boolean wide:new boolean[]{false,true}){
                    List<Map<String,Object>> sql=repository.navigationVertices(id,target,500000,6000000,dn,width,wide);
                    List<Map<String,Object>> memory=engine.corridorVertices(id,500000,6000000,dn,width,wide,wide?30001:8001);
                    Set<String> a=keys(sql),b=keys(memory);
                    Set<String> onlySql=new TreeSet<>(a);onlySql.removeAll(b);Set<String> onlyMemory=new TreeSet<>(b);onlyMemory.removeAll(a);
                    assertTrue(onlySql.size()<=Math.max(2,a.size()/100)&&onlyMemory.size()<=Math.max(2,b.size()/100),
                        "round "+round+" dn "+dn+" wide "+wide+": sql "+a.size()+" memory "+b.size()+" only-sql "+onlySql+" only-memory "+onlyMemory);
                    assertFalse(a.isEmpty(),"the fixture must produce a graph");
                }
            }
        }
    }
}
