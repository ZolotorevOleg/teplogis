package ru.lct.heat.geojson;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.math.BigDecimal;

/** Сохраняет исходный JSON-тип (строка/число) идентификатора, прочитанного из PostgreSQL jsonb. */
@Component
public class JsonIds {
    private final ObjectMapper mapper;
    public JsonIds(ObjectMapper mapper){this.mapper=mapper;}
    public Object parse(Object databaseValue){
        String json=String.valueOf(databaseValue);
        try {JsonNode node=mapper.readTree(json);if(node.isTextual())return node.textValue();if(node.isIntegralNumber())return node.bigIntegerValue();if(node.isNumber())return node.decimalValue();}
        catch(IOException ignored){}
        throw new IllegalStateException("Stored object_id is not a JSON string or number");
    }
    public static String key(Object id){
        if(id instanceof Number)return "n:"+new BigDecimal(id.toString()).stripTrailingZeros().toPlainString();
        return "s:"+String.valueOf(id);
    }
}
