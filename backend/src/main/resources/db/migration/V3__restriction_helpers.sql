-- All functions are pure geometry operations; they never repair or alter input features.
CREATE FUNCTION lct_obstacle_component(p_import uuid,p_ordinal bigint,p_path text)
RETURNS geometry LANGUAGE sql STABLE AS $$
 SELECT d.geom FROM input_geometries g CROSS JOIN LATERAL ST_Dump(g.geom_metric) d
 WHERE g.import_id=p_import AND g.ordinal=p_ordinal AND d.path::text=p_path
$$;

CREATE FUNCTION lct_own_oks_approach(g geometry,s geometry)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT AS $$
 WITH x AS (SELECT ST_Intersection(g,s) AS hit,ST_EndPoint(s) AS target),
 v AS (SELECT *,ST_Distance(target,ST_Boundary(g)) AS nearest FROM x)
 SELECT ST_Covers(g,target) AND NOT ST_Contains(g,ST_StartPoint(s)) AND
   ((GeometryType(hit)='POINT' AND ST_DWithin(hit,target,0.000001)) OR
    (GeometryType(hit)='LINESTRING' AND
      (ST_DWithin(ST_StartPoint(hit),target,0.000001) OR ST_DWithin(ST_EndPoint(hit),target,0.000001)) AND
      abs(ST_Length(hit)-nearest)<=0.000001)) FROM v
$$;

CREATE FUNCTION lct_crossing_angle(g geometry,s geometry,p geometry)
RETURNS double precision LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT min(degrees(acos(least(1.0,abs(cos(ST_Azimuth(ST_StartPoint(s),ST_EndPoint(s))-
     ST_Azimuth(ST_StartPoint(e.geom),ST_EndPoint(e.geom))))))))
 FROM ST_DumpSegments(g) e
 WHERE ST_Length(e.geom)>0 AND ST_DWithin(e.geom,p,0.000001)
$$;

-- A line touching the candidate on only one side (including an endpoint) does not cross it.
CREATE FUNCTION lct_line_crosses_at(g geometry,s geometry,p geometry)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT AS $$
 WITH points AS (
   SELECT ST_StartPoint(e.geom) AS q FROM ST_DumpSegments(g) e WHERE ST_DWithin(e.geom,p,0.000001)
   UNION ALL
   SELECT ST_EndPoint(e.geom) AS q FROM ST_DumpSegments(g) e WHERE ST_DWithin(e.geom,p,0.000001)
 ), sides AS (
   SELECT ((ST_X(ST_EndPoint(s))-ST_X(ST_StartPoint(s)))*(ST_Y(q)-ST_Y(p))-
     (ST_Y(ST_EndPoint(s))-ST_Y(ST_StartPoint(s)))*(ST_X(q)-ST_X(p)))/ST_Length(s) AS side FROM points
 ) SELECT coalesce(min(side)<-0.000001 AND max(side)>0.000001,false) FROM sides
$$;

CREATE FUNCTION lct_crossing_events(g geometry,s geometry)
RETURNS TABLE(from_m double precision,to_m double precision,angle_deg double precision,crosses boolean)
LANGUAGE sql IMMUTABLE STRICT AS $$
 WITH hits AS (SELECT d.geom FROM ST_Dump(ST_Intersection(g,s)) d WHERE NOT ST_IsEmpty(d.geom)),
 positions AS (
  SELECT geom,GeometryType(geom) AS kind,
   CASE WHEN GeometryType(geom)='POINT' THEN ST_LineLocatePoint(s,geom)
    ELSE least(ST_LineLocatePoint(s,ST_StartPoint(geom)),ST_LineLocatePoint(s,ST_EndPoint(geom))) END AS a,
   CASE WHEN GeometryType(geom)='POINT' THEN ST_LineLocatePoint(s,geom)
    ELSE greatest(ST_LineLocatePoint(s,ST_StartPoint(geom)),ST_LineLocatePoint(s,ST_EndPoint(geom))) END AS b
  FROM hits
 ) SELECT a*ST_Length(s),b*ST_Length(s),lct_crossing_angle(g,s,ST_LineInterpolatePoint(s,a)),
   CASE WHEN GeometryType(g)='POLYGON' THEN kind='LINESTRING' AND ST_Contains(g,ST_LineInterpolatePoint(s,(a+b)/2))
     ELSE kind='POINT' AND lct_line_crosses_at(g,s,geom) END
 FROM positions ORDER BY a,b
$$;
