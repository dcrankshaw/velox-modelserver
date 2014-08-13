package edu.berkeley.veloxms.resources;

import edu.berkeley.veloxms.storage.ModelStorage;
import io.dropwizard.jersey.params.LongParam;
import com.codahale.metrics.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/predict-item/{item}/{user}")
@Produces(MediaType.APPLICATION_JSON)
public class PredictItemResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(PredictItemResource.class);
    private final ModelStorage model;

    public PredictItemResource(ModelStorage model) {
        this.model = model;
    }

    @GET
    @Timed
    public double getPrediction(@PathParam("user") LongParam userId,
            @PathParam("item") LongParam itemId) {
        double[] userFeatures = model.getUserFactors(userId.get().longValue());
        double[] itemFeatures = model.getItemFactors(itemId.get().longValue());
        return makePrediction(userFeatures, itemFeatures);
    }

    private double makePrediction(double[] userFeatures, double[] itemFeatures) {
        double sum = 0;
        for (int i = 0; i < userFeatures.length; ++i) {
            sum += itemFeatures[i]*userFeatures[i];
        }
        return sum;
    }

}
