-- Match the documented metric tolerance at a final approach's boundary start as well.
CREATE OR REPLACE FUNCTION lct_own_oks_approach(g geometry,s geometry)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT AS $$
 WITH x AS (SELECT ST_Intersection(g,s) AS hit,ST_EndPoint(s) AS target),
 v AS (SELECT *,ST_Distance(target,ST_Boundary(g)) AS nearest FROM x)
 SELECT ST_Covers(g,target) AND
   (NOT ST_Contains(g,ST_StartPoint(s)) OR ST_DWithin(ST_Boundary(g),ST_StartPoint(s),0.000001)) AND
   -- The closed ball up to the nearest boundary lies inside g. This shortest approach
   -- test avoids an empty GEOS overlay at large coordinates within rounding tolerance.
   (abs(ST_Length(s)-nearest)<=0.000001 OR
    (GeometryType(hit)='POINT' AND ST_DWithin(hit,target,0.000001)) OR
    (GeometryType(hit)='LINESTRING' AND
      (ST_DWithin(ST_StartPoint(hit),target,0.000001) OR ST_DWithin(ST_EndPoint(hit),target,0.000001)) AND
      abs(ST_Length(hit)-nearest)<=0.000001)) FROM v
$$;

