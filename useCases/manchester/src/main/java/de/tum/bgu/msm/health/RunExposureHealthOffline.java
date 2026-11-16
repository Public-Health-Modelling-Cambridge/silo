package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.Zone;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.health.airPollutant.AirPollutantModel;
import de.tum.bgu.msm.health.data.LinkInfo;
import de.tum.bgu.msm.health.data.ActivityLocation;
import de.tum.bgu.msm.health.data.PersonHealth;
import de.tum.bgu.msm.health.disease.Diseases;
//import de.tum.bgu.msm.health.io.CarDriverShareTableReader;
import de.tum.bgu.msm.health.io.PrevalenceDataReader;
import de.tum.bgu.msm.health.noise.NoiseModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.utils.SiloUtil;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import de.tum.bgu.msm.health.injury.AccidentModel;

import java.util.*;

/**
 * Implements SILO for the Greater Manchester
 *
 * @author Qin Zhang*/


public class RunExposureHealthOffline {

    private final static Logger logger = LogManager.getLogger(RunExposureHealthOffline.class);

    public static void main(String[] args) {
        run(args[0], args.length > 1 ? args[1] : null, 2021);
    }

    /**
     * Runs the models for 2021. The disease model then runs the further years up to lastDiseaseYear with the 2021
     * exposures, without ageing or demography: the regression test uses this to show disease state changes over several
     * years, e.g. remission.
     */
    public static void run(String propertiesFile, String configFile, int lastDiseaseYear) {

        Properties properties = SiloUtil.siloInitialization(propertiesFile);

        // todo: check if that is good practice/ necessary to run the accident model, but need to make sure there are no implications elsewhere
        Resources.initializeResources(properties.transportModel.mitoPropertiesPath);

        Config config = null;
        if (configFile != null) {
            config = ConfigUtils.loadConfig(configFile);
        }
        logger.info("Started SILO land use model for the Greater Manchester");
        HealthDataContainerImpl dataContainer = DataBuilderHealth.getModelDataForManchester(properties, config);
        DataBuilderHealth.read(properties, dataContainer, config);

        // setup
        AccidentModelMCR accidentModel = new AccidentModelMCR(dataContainer, properties, SiloUtil.provideNewRandom());
        HealthExposureModelMCR exposureModelMCR = new HealthExposureModelMCR(dataContainer, properties, SiloUtil.provideNewRandom(),config);
        SportPAModelMCR sportPAModelMCR = new SportPAModelMCR(dataContainer, properties, SiloUtil.provideNewRandom());
        DiseaseModelMCR diseaseModelMCR = new DiseaseModelMCR(dataContainer, properties, SiloUtil.provideNewRandom());

        // disease model only
        // exposureModelMCR.setup();

        // runs
        accidentModel.endYear(2021);
        // sport PA before exposure: activity exposure uses the person's sport mMET-hours for the ventilation rate
        sportPAModelMCR.endYear(2021);
        //exposureModelMCR.setup(); // read-in the exposure file
        exposureModelMCR.endYear(2021);
        diseaseModelMCR.setup();
        for (int year = 2021; year <= lastDiseaseYear; year++) {
            diseaseModelMCR.endYear(year);
        }
        dataContainer.endSimulation();

        logger.info("Finished SILO.");
    }
}
