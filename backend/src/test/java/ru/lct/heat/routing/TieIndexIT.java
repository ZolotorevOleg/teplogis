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
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The in-memory tie index must return the same candidates as the reference SQL, for random networks. */
@SpringBootTest @Testcontainers
class TieIndexIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:14-3.4").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r){r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);}
    @Autowired JdbcTemplate jdbc;
    @Autowired RouteRepository repository;
    @Autowired ru.lct.heat.network.NetworkRepository networkRepository;
    UUID id;long next;

    void add(String objectType,String wkt){
        long ordinal=next++;
        jdbc.update("INSERT INTO input_objects(import_id,ordinal,object_id,object_type) VALUES(?,?,?,?)",id,ordinal,"\""+objectType+"-"+ordinal+"\"",objectType);
        jdbc.update("INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,restriction_type,outside_utm_area,simple,length_m,area_m2) SELECT ?,?,ST_Transform(g,4326),g,?,?,false,ST_IsSimple(g),ST_Length(g),ST_Area(g) FROM (SELECT ST_Translate(ST_GeomFromText(?,32637),500000,6000000) g) q",
            id,ordinal,objectType.equals("heat_network")?200:null,null,wkt);
    }

    static String key(Map<String,Object> row){
        return row.get("ordinal")+"|"+Math.round(((Number)row.get("x")).doubleValue()*1e4)+"|"+Math.round(((Number)row.get("y")).doubleValue()*1e4);
    }
    Set<String> keys(List<Map<String,Object>> rows){Set<String> s=new TreeSet<>();for(Map<String,Object> r:rows)s.add(key(r));return s;}

    @Test void inMemoryCandidatesMatchTheSqlReferenceOnRandomNetworks(){
        Random random=new Random(20260924);
        for(int round=0;round<6;round++){
            id=UUID.randomUUID();next=0;
            jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);
            List<double[]> chamberSpots=new ArrayList<>();
            int lineCount=3+random.nextInt(4);
            for(int l=0;l<lineCount;l++){
                double x=random.nextInt(600)-300,y=random.nextInt(600)-300;StringBuilder wkt=new StringBuilder("LINESTRING("+x+" "+y);
                int vertices=1+random.nextInt(4);
                for(int v=0;v<vertices;v++){x+=random.nextInt(300)-100;y+=random.nextInt(300)-100;wkt.append(",").append(x).append(" ").append(y);}
                wkt.append(")");add("heat_network",wkt.toString());chamberSpots.add(new double[]{x,y});
            }
            // chambers at line ends (attachments 1) and one duplicated spot so some chambers are "full"
            for(double[] spot:chamberSpots)add("heat_chamber","POINT("+spot[0]+" "+spot[1]+")");
            for(int t=0;t<5;t++){
                double tx=500000+random.nextInt(700)-350,ty=6000000+random.nextInt(700)-350;
                for(double spacing:new double[]{25.0,100.0}){
                    for(boolean prefer:new boolean[]{false,true}){
                        List<Map<String,Object>> sql=repository.tieCandidatesSql(id,tx,ty,spacing,prefer,Set.of(),400);
                        List<Map<String,Object>> memory=repository.tieCandidates(id,tx,ty,spacing,prefer,Set.of(),400);
                        assertEquals(keys(sql),keys(memory),"round "+round+" target "+t+" spacing "+spacing+" prefer "+prefer);
                    }
                }
            }
        }
    }

    @Test void inMemoryChambersNearMatchTheSqlReference(){
        Random random=new Random(20260925);
        for(int round=0;round<5;round++){
            id=UUID.randomUUID();next=0;
            jdbc.update("INSERT INTO imports(id,byte_count,status,geometry_status) VALUES(?,0,'READY','READY')",id);
            List<double[]> spots=new ArrayList<>();
            for(int l=0;l<4+random.nextInt(3);l++){
                double x=random.nextInt(400)-200,y=random.nextInt(400)-200;StringBuilder wkt=new StringBuilder("LINESTRING("+x+" "+y);
                for(int v=0;v<1+random.nextInt(3);v++){x+=random.nextInt(200)-60;y+=random.nextInt(200)-60;wkt.append(",").append(x).append(" ").append(y);}
                wkt.append(")");add("heat_network",wkt.toString());spots.add(new double[]{x,y});
            }
            for(double[] s:spots)add("heat_chamber","POINT("+s[0]+" "+s[1]+")");
            for(int t=0;t<8;t++){
                double x=500000+random.nextInt(500)-250,y=6000000+random.nextInt(500)-250;
                for(double radius:new double[]{10,30}){
                    List<Map<String,Object>> sql=networkRepository.chambersNearSql(id,x,y,radius);
                    List<Map<String,Object>> memory=networkRepository.chambersNear(id,x,y,radius);
                    assertEquals(sql.size(),memory.size(),"round "+round+" radius "+radius);
                    for(int i=0;i<sql.size();i++){
                        assertEquals(((Number)sql.get(i).get("ordinal")).longValue(),((Number)memory.get(i).get("ordinal")).longValue());
                        assertEquals(((Number)sql.get(i).get("existing_attachments")).intValue(),((Number)memory.get(i).get("existing_attachments")).intValue());
                        assertEquals(((Number)sql.get(i).get("x")).doubleValue(),((Number)memory.get(i).get("x")).doubleValue(),1e-6);
                        assertEquals(((Number)sql.get(i).get("y")).doubleValue(),((Number)memory.get(i).get("y")).doubleValue(),1e-6);
                        assertEquals(sql.get(i).get("id_json"),memory.get(i).get("id_json"));
                    }
                }
            }
        }
    }
}
