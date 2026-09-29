ALTER TABLE imports ADD COLUMN geometry_status varchar(20) NOT NULL DEFAULT 'NOT_PREPARED'
    CHECK (geometry_status IN ('NOT_PREPARED','READY'));
ALTER TABLE imports ADD COLUMN geometry_prepared_at timestamptz;

CREATE TABLE input_geometries (
    import_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    geom_wgs84 geometry(Geometry,4326) NOT NULL,
    geom_metric geometry(Geometry,32637) NOT NULL,
    diameter numeric,
    flow_tph numeric,
    restriction_type text,
    outside_utm_area boolean NOT NULL,
    simple boolean NOT NULL,
    length_m double precision NOT NULL,
    area_m2 double precision NOT NULL,
    PRIMARY KEY(import_id,ordinal),
    FOREIGN KEY(import_id,ordinal) REFERENCES input_objects(import_id,ordinal) ON DELETE CASCADE,
    CHECK(ST_NDims(geom_wgs84)=2 AND ST_NDims(geom_metric)=2),
    CHECK(NOT ST_IsEmpty(geom_wgs84) AND NOT ST_IsEmpty(geom_metric)),
    CHECK(ST_IsValid(geom_wgs84) AND ST_IsValid(geom_metric))
);
CREATE INDEX input_geometries_metric_gist ON input_geometries USING gist(geom_metric);
CREATE INDEX input_geometries_wgs84_gist ON input_geometries USING gist(geom_wgs84);
CREATE INDEX input_objects_import_type ON input_objects(import_id,object_type);

CREATE FUNCTION lct_prepare_geometry(p_import uuid, p_ordinal bigint, p_wkb bytea,
    p_diameter numeric, p_flow numeric, p_restriction text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    w geometry; m geometry; detail valid_detail; line_length double precision; polygon_area double precision;
BEGIN
    w := ST_GeomFromWKB(p_wkb,4326);
    detail := ST_IsValidDetail(w);
    IF ST_IsEmpty(w) OR NOT detail.valid THEN
        RAISE EXCEPTION USING ERRCODE='22023', MESSAGE='Invalid WGS84 geometry: ' || coalesce(detail.reason,'Empty geometry');
    END IF;
    BEGIN
        m := ST_Transform(w,32637);
    EXCEPTION WHEN internal_error OR invalid_parameter_value THEN
        RAISE EXCEPTION USING ERRCODE='22023', MESSAGE='Cannot transform geometry to EPSG:32637';
    END;
    detail := ST_IsValidDetail(m);
    IF ST_IsEmpty(m) OR NOT detail.valid THEN
        RAISE EXCEPTION USING ERRCODE='22023', MESSAGE='Invalid metric geometry: ' || coalesce(detail.reason,'Empty geometry');
    END IF;
    IF NOT (ST_XMin(Box3D(m)) > '-Infinity'::float8 AND ST_XMax(Box3D(m)) < 'Infinity'::float8
        AND ST_YMin(Box3D(m)) > '-Infinity'::float8 AND ST_YMax(Box3D(m)) < 'Infinity'::float8) THEN
        RAISE EXCEPTION USING ERRCODE='22023', MESSAGE='Non-finite projected coordinates';
    END IF;
    line_length := ST_Length(m); polygon_area := ST_Area(m);
    IF GeometryType(m) IN ('LINESTRING','MULTILINESTRING') AND line_length <= 0 THEN
        RAISE EXCEPTION USING ERRCODE='22023', MESSAGE='Zero-length line';
    END IF;
    INSERT INTO input_geometries(import_id,ordinal,geom_wgs84,geom_metric,diameter,flow_tph,
        restriction_type,outside_utm_area,simple,length_m,area_m2)
    VALUES(p_import,p_ordinal,w,m,p_diameter,p_flow,p_restriction,
        NOT ST_CoveredBy(w,ST_MakeEnvelope(36,0,42,84,4326)),ST_IsSimple(m),line_length,polygon_area);
END;
$$;
