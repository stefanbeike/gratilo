package com.gratilo.payment.api;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Deserializer;
import jakarta.inject.Singleton;
import java.io.IOException;

/** Prevent the default Long decoder from silently truncating fractional JSON cents. */
@Singleton
public final class MinorUnitsDeserializer implements Deserializer<Long> {
    @Override public Long deserialize(Decoder decoder,DecoderContext context,Argument<? super Long> type)throws IOException {
        try{return decoder.decodeBigDecimal().longValueExact();}
        catch(ArithmeticException invalid){throw decoder.createDeserializationException("Beträge müssen als ganze Cent ohne Überlauf angegeben werden.",null);}
    }
}
