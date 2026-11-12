package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import io.smallrye.config.*;
import org.junit.jupiter.api.Test;

class EnvironmentAliasesTest {
    @Test void bothDistributionsAcceptLegacySettingsAndPreferNewSettingsIncludingSecrets()throws Exception{
        for(String file:List.of("src/main/resources/application.properties","../connect/src/main/resources/application.properties")){
            var values=new Properties();try(var input=Files.newInputStream(Path.of(file))){values.load(input);}
            for(String key:values.stringPropertyNames()){
                String expression=values.getProperty(key);if(!expression.startsWith("${DAGSVERK_"))continue;
                String name=expression.substring(2,expression.indexOf(':')),legacy="TIMEKEEPER_"+name.substring(9);
                for(var environment:List.of(Map.of(legacy,"old-private-value"),Map.of(legacy,"old-private-value",name,"new-private-value"))){
                    var config=new SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new PropertiesConfigSource(Map.of(key,expression),"application",100),new EnvConfigSource(environment,300),new DagsverkEnvironmentConfigSource(environment)).build();
                    assertEquals(environment.containsKey(name)?"new-private-value":"old-private-value",config.getValue(key,String.class),key);
                }
                var cleared=Map.of(legacy,"old-private-value",name,"");
                var config=new SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new PropertiesConfigSource(Map.of(key,expression),"application",100),new EnvConfigSource(cleared,300),new DagsverkEnvironmentConfigSource(cleared)).build();
                assertEquals("",config.getOptionalValue(key,String.class).orElse(""),key);
            }
        }
    }
    @Test void diagnosticsOnlyNameConflictingSettingsAndDoNotExposeTheirValues(){
        var environment=Map.of("TIMEKEEPER_CONNECT_ENCRYPTION_KEY","private-legacy","DAGSVERK_CONNECT_ENCRYPTION_KEY","private-new","TIMEKEEPER_CONNECT_ORIGIN","same","DAGSVERK_CONNECT_ORIGIN","same");
        assertEquals(List.of("DAGSVERK_CONNECT_ENCRYPTION_KEY"),EnvironmentAliases.conflicts(environment));assertFalse(EnvironmentAliases.conflicts(environment).toString().contains("private"));
        assertEquals("private-new",EnvironmentAliases.value(environment,"DAGSVERK_CONNECT_ENCRYPTION_KEY"));assertEquals("old",EnvironmentAliases.value(Map.of("TIMEKEEPER_SMTP_PASSWORD","old"),"DAGSVERK_SMTP_PASSWORD"));
        assertEquals("",EnvironmentAliases.value(Map.of("TIMEKEEPER_SMTP_PASSWORD","old","DAGSVERK_SMTP_PASSWORD",""),"DAGSVERK_SMTP_PASSWORD"));
    }
}
