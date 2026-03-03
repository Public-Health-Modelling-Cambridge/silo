
![CI status](https://github.com/msmobility/silo/workflows/Java%20CI/badge.svg)
[![Cloudsmith Deployment](https://github.com/msmobility/silo/actions/workflows/deployment.yml/badge.svg?branch=master)](https://github.com/msmobility/silo/actions/workflows/deployment.yml)
[![Hosted By: Cloudsmith](https://img.shields.io/badge/OSS%20hosting%20by-cloudsmith-blue?logo=cloudsmith&style=for-the-badge)](https://cloudsmith.com)

---

# SILO Model

## Project Overview

**Repository:** Public-Health-Modelling-Cambridge/silo  
**Description:** SILO Model Java Code  
**Language Composition:** Java (85.7%), HTML (14.1%), R (0.2%)  
**Version:** 0.1.0-SNAPSHOT  
**License:** GNU General Public License

### What is SILO?

SILO stands for **Simple Integrated Land-Use Orchestrator**. It is a sophisticated Java-based microsimulation model designed to simulate population, housing, and land-use dynamics. The model can simulate behavioral changes in households and individuals over time, making it suitable for public health modeling, urban planning, and transportation analysis.

**Official Resources:**
- High-level overview: [www.silo.zone](http://www.silo.zone)
- Technical documentation: [TUM Wiki](https://wiki.tum.de/display/msmmodels/SILO)

---

## Repository Structure

The repository is organized as a Maven multi-module project with the following main components:

### Core Modules

```
Public-Health-Modelling-Cambridge/silo/
├── siloCore/              # Core SILO simulation engine
├── useCases/              # Region-specific implementations (9 use cases)
├── extensions/            # Optional extensions and integrations
├── analysis/              # Analysis tools
├── synthetic-population/  # Synthetic population generation utilities
└── lib/                   # External libraries
```

### Key Modules

#### **1. siloCore**
The main simulation engine implementing:
- **Data Structures:** Person, Household, Dwelling, Job classes
- **Event Model:** Microsimulation using event-based programming
- **Core Features:**
  - Person lifecycle (birth, death, aging, birthday)
  - Household formation (marriage, divorce)
  - Employment and income dynamics
  - Dwelling allocation and relocation
  - Transportation and land-use interactions

**Key Dependencies:**
- GeoTools (geospatial data handling)
- JTS (geometry processing)
- MATSim (transportation modeling framework)
- JCommon (charting library)

#### **2. useCases**
Pre-configured regional implementations for specific locations:
- **munich** - Munich region
- **maryland** - Maryland region (USA)
- **perth** - Perth region (Australia)
- **kagawa** - Kagawa region (Japan)
- **austin** - Austin region (USA)
- **capeTown** - Cape Town region (South Africa)
- **fabiland** - FABILand test case
- **bangkok** - Bangkok region (Thailand)
- **manchester** - Manchester region (UK)

Each use case inherits from siloCore and provides:
- Customized model configurations
- Regional parameters
- Specific behavioral strategies

#### **3. extensions**
Optional extensions that augment the core model:
- **matsim2silo** - Integration with MATSim transportation models
- **mito2silo** - Integration with MITO travel demand model
- **schools** - School location choice modeling
- **health** - Health impact assessment module

#### **4. analysis**
Analysis tools for processing simulation outputs and studying model results across different use cases.

#### **5. synthetic-population**
Tools for generating synthetic populations for model initialization:
- Population generation algorithms
- IPU (Iterative Proportional Updating) methods
- Support for multiple regions

---

## Core Architecture & Key Classes

### Primary Data Models

```
Person (interface)
├── Attributes: age, gender, income, occupation, role, driver license
├── Relationships: household, workplace
└── Methods: income adjustment, license management, custom attributes

Household
├── Members: collection of persons
├── Housing: dwelling unit reference
├── Economics: income, car ownership
└── Lifecycle: formation, dissolution, composition changes

Dwelling
├── Location: zone, coordinates
├── Characteristics: size, year built, quality, rent/price
└── Occupants: household reference

Job
├── Location: zone
├── Characteristics: sector, income requirements
└── Holder: person reference
```

### Event-Driven Simulation Engine

The **SiloModel** class manages:
1. **Event Registration** - MicroEvent types with corresponding EventModel handlers
2. **Annual Updates** - ModelUpdateListener implementations for yearly operations
3. **Results Monitoring** - ResultsMonitor interfaces for output tracking
4. **Time Management** - TimeTracker for simulation progression
5. **Data Containers** - DataContainer and ModelContainer for centralized data access

**Key Simulation Components:**
```java
public final class SiloModel {
    private Simulator simulator;
    private DataContainer dataContainer;
    private ModelContainer modelContainer;
    private Set<ResultsMonitor> resultsMonitors;
    
    public void runModel() {
        // Setup → RunYearByYear → EndSimulation
    }
}
```

---

## Key Features & Modules

### Behavioral Models

1. **Birth Model** - Population growth simulation
2. **Death Model** - Mortality dynamics
3. **Birthday Model** - Age progression
4. **Marriage/Divorce** - Household formation changes
5. **Moves Model** - Residential relocation with housing utility choice
6. **Education Model** - Educational attainment progression
7. **Drivers License Model** - Vehicle license ownership
8. **Employment Model** - Job allocation and income dynamics
9. **Car Ownership Model** - Vehicle ownership patterns

### Land-Use & Real Estate

- **Construction Model** - New building development
- **Demolition Model** - Building removal
- **Renovation Model** - Building improvements
- **Pricing Model** - Real estate valuation
- **Dwelling Allocation** - Housing market matching

### Transport Integration

- **MATSim Integration** - Full integration with MATSim framework
- **Mode Choice** - Travel mode selection utilities
- **Commute Matching** - Work-to-home location matching (Gale-Shapley algorithm)
- **Accessibility** - Travel time and impedance calculations

### Health Module (Extensions)

The health extension provides:
- **Physical Activity Calculation** - Mode-specific activity METs (Metabolic Equivalent of Task)
- **Air Quality Exposure** - PM2.5 and NO2 modeling
- **Health Impact Assessment** - Disease risk calculations
- **Trip Analysis** - Purpose and mode-based trip tracking

---

## Build & Development Setup

### Requirements
- **Java:** JDK 8+
- **Build Tool:** Maven 3.6+
- **IDE:** Eclipse (recommended)

### Building the Project
```bash
mvn clean install
```

### Loading into Eclipse
1. Clone the repository: `git clone <repo-url>`
2. In Eclipse: **File → Import → Maven → Existing Maven Projects**
3. Browse to the repository location
4. Select all modules and import

---

## Dependencies

**Key External Libraries:**
- **MATSim** - Open-source multi-agent transport simulation (matsim.org)
- **GeoTools** - Java GIS toolkit for geospatial data
- **JTS (Java Topology Suite)** - Geometry operations
- **JUnit Jupiter** - Testing framework
- **Log4j 2** - Logging

**Repository Management:**
- Artifacts hosted on Cloudsmith (https://cloudsmith.com)
- Multiple Maven repositories configured for dependencies

---

## CI/CD & Deployment

- **CI:** GitHub Actions with Java CI workflow
- **Deployment:** Automated deployment to Cloudsmith package repository
- **Versioning:** 0.1.0-SNAPSHOT (development version)

---

## Use Cases & Applications

SILO can be applied to:
- Urban growth and land-use simulation
- Population projections
- Housing market dynamics
- Transportation demand modeling
- Public health impact assessment
- Environmental exposure analysis
- Policy scenario testing

The repository includes implemented use cases for 9 international regions, providing templates for new applications.

---

## Documentation & References

- **Official Website:** www.silo.zone
- **Technical Wiki:** https://wiki.tum.de/display/msmmodels/SILO
- **Package Repository:** Cloudsmith (OSS hosting)
- **Code License:** GNU General Public License (full LICENSE file included)

---

**For more information on specific components or classes, please refer to the inline code documentation and the technical wiki referenced above.**
