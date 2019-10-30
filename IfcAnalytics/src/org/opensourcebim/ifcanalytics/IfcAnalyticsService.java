package org.opensourcebim.ifcanalytics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bimserver.bimbots.BimBotContext;
import org.bimserver.bimbots.BimBotsException;
import org.bimserver.bimbots.BimBotsInput;
import org.bimserver.bimbots.BimBotsOutput;
import org.bimserver.emf.IdEObject;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.models.geometry.Bounds;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.Ifc2x3tc1Package;
import org.bimserver.models.ifc2x3tc1.IfcBuilding;
import org.bimserver.models.ifc2x3tc1.IfcBuildingStorey;
import org.bimserver.models.ifc2x3tc1.IfcCalendarDate;
import org.bimserver.models.ifc2x3tc1.IfcClassification;
import org.bimserver.models.ifc2x3tc1.IfcClassificationNotationSelect;
import org.bimserver.models.ifc2x3tc1.IfcClassificationReference;
import org.bimserver.models.ifc2x3tc1.IfcGroup;
import org.bimserver.models.ifc2x3tc1.IfcMaterial;
import org.bimserver.models.ifc2x3tc1.IfcMaterialLayer;
import org.bimserver.models.ifc2x3tc1.IfcMaterialLayerSet;
import org.bimserver.models.ifc2x3tc1.IfcMaterialLayerSetUsage;
import org.bimserver.models.ifc2x3tc1.IfcMaterialSelect;
import org.bimserver.models.ifc2x3tc1.IfcObjectDefinition;
import org.bimserver.models.ifc2x3tc1.IfcPostalAddress;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcRelAssigns;
import org.bimserver.models.ifc2x3tc1.IfcRelAssignsToGroup;
import org.bimserver.models.ifc2x3tc1.IfcRelAssociatesClassification;
import org.bimserver.models.ifc2x3tc1.IfcRelAssociatesMaterial;
import org.bimserver.models.ifc2x3tc1.IfcRelDecomposes;
import org.bimserver.models.ifc2x3tc1.IfcRoot;
import org.bimserver.models.ifc2x3tc1.IfcSpace;
import org.bimserver.models.ifc2x3tc1.IfcZone;
import org.bimserver.models.store.BooleanType;
import org.bimserver.models.store.IfcHeader;
import org.bimserver.models.store.ObjectDefinition;
import org.bimserver.models.store.ParameterDefinition;
import org.bimserver.models.store.PrimitiveDefinition;
import org.bimserver.models.store.PrimitiveEnum;
import org.bimserver.models.store.StoreFactory;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.services.BimBotAbstractService;
import org.bimserver.plugins.services.BimBotClient;
import org.bimserver.plugins.services.BimBotExecutionException;
import org.bimserver.utils.AreaUnit;
import org.bimserver.utils.IfcUtils;
import org.bimserver.utils.LengthUnit;
import org.bimserver.utils.VolumeUnit;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;

public class IfcAnalyticsService extends BimBotAbstractService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final AreaUnit DEFAULT_AREA_UNIT = AreaUnit.SQUARED_METER;
	private static final VolumeUnit DEFAULT_VOLUME_UNIT = VolumeUnit.CUBIC_METER;
	
	private AreaUnit modelAreaUnit;
	private VolumeUnit modelVolumeUnit;
	private LengthUnit modelLengthUnit;

	private int cubesNearZero;

	public boolean preloadCompleteModel() {
		return true;
	}
	
	@Override
	public ObjectDefinition getUserSettingsDefinition() {
		ObjectDefinition settingsDefinition = StoreFactory.eINSTANCE.createObjectDefinition();
		
		PrimitiveDefinition booleanType = StoreFactory.eINSTANCE.createPrimitiveDefinition();
		booleanType.setType(PrimitiveEnum.BOOLEAN);

		PrimitiveDefinition stringType = StoreFactory.eINSTANCE.createPrimitiveDefinition();
		stringType.setType(PrimitiveEnum.STRING);
		
		BooleanType falseValue = StoreFactory.eINSTANCE.createBooleanType();
		falseValue.setValue(false);
		
		ParameterDefinition clashDetectionEnabled = StoreFactory.eINSTANCE.createParameterDefinition();
		clashDetectionEnabled.setName("Clash detection enabled");
		clashDetectionEnabled.setIdentifier("clashdetectionenabled");
		clashDetectionEnabled.setType(booleanType);
		clashDetectionEnabled.setDefaultValue(falseValue);
		settingsDefinition.getParameters().add(clashDetectionEnabled);
		
		ParameterDefinition clashDetectionToken = StoreFactory.eINSTANCE.createParameterDefinition();
		clashDetectionToken.setName("Clash detection token");
		clashDetectionToken.setIdentifier("clashdetectiontoken");
		clashDetectionToken.setType(stringType);
		settingsDefinition.getParameters().add(clashDetectionToken);

		ParameterDefinition clashDetectionUrl = StoreFactory.eINSTANCE.createParameterDefinition();
		clashDetectionUrl.setName("Clash detection url");
		clashDetectionUrl.setIdentifier("clashdetectionurl");
		clashDetectionUrl.setType(stringType);
		settingsDefinition.getParameters().add(clashDetectionUrl);

		ParameterDefinition clashDetectionIdentifier = StoreFactory.eINSTANCE.createParameterDefinition();
		clashDetectionIdentifier.setName("Clash detection identifier");
		clashDetectionIdentifier.setIdentifier("clashdetectionidentifier");
		clashDetectionIdentifier.setType(stringType);
		settingsDefinition.getParameters().add(clashDetectionIdentifier);
		
		return settingsDefinition;
	}
	
	@Override
	public BimBotsOutput runBimBot(BimBotsInput input, BimBotContext bimBotContext, PluginConfiguration pluginConfiguration) throws BimBotsException {
		IfcModelInterface model = input.getIfcModel();

		modelLengthUnit = IfcUtils.getLengthUnit(model);
		modelAreaUnit = IfcUtils.getAreaUnit(model);
		modelVolumeUnit = IfcUtils.getVolumeUnit(model);
		
		ObjectNode result = OBJECT_MAPPER.createObjectNode();
		result.set("header", proccessIfcHeader(model.getModelMetaData().getIfcHeader()));
		result.set("project", processProject(model));
		result.set("materials", processMaterials(model));
		result.set("classifications", processClassification(model));
		result.set("aggregations", processAggregations(model));
		result.set("checks", processChecks(input, pluginConfiguration));

		BimBotsOutput bimBotsOutput = new BimBotsOutput("IFC_ANALYTICS_JSON_1_0", result.toString().getBytes(Charsets.UTF_8));
		bimBotsOutput.setTitle("Ifc Analytics Results");
		bimBotsOutput.setContentType("application/json");

		return bimBotsOutput;
	}

	private ArrayNode callClashDetectionService(BimBotsInput input, String url, String token, String identifier) {
		try (BimBotClient bimBotClient = new BimBotClient(url, token)) {
			BimBotsOutput bimBotsOutput = bimBotClient.call(identifier, input);
			return OBJECT_MAPPER.readValue(bimBotsOutput.getData(), ArrayNode.class);
		} catch (BimBotExecutionException e) {
			e.printStackTrace();
		} catch (JsonParseException e) {
			e.printStackTrace();
		} catch (JsonMappingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	private JsonNode processChecks(BimBotsInput input, PluginConfiguration pluginConfiguration) {
		ObjectNode checksNode = OBJECT_MAPPER.createObjectNode();
		if (pluginConfiguration.has("clashdetectionenabled")) {
			if (pluginConfiguration.getBoolean("clashdetectionenabled")) {
				String url = pluginConfiguration.getString("clashdetectionurl");
				String identifier = pluginConfiguration.getString("clashdetectionidentifier");
				String token = pluginConfiguration.getString("clashdetectiontoken");
				checksNode.set("clashes", callClashDetectionService(input, url, token, identifier));
			}
		}
		checksNode.put("hasCubeNearZero", cubesNearZero == 1);
		return checksNode;
	}

	private JsonNode processAggregations(IfcModelInterface model) {
		ObjectNode aggregations = OBJECT_MAPPER.createObjectNode();
		ObjectNode perType = OBJECT_MAPPER.createObjectNode();
		aggregations.set("perType", perType);

		List<ObjectDetails> objects = new ArrayList<>();
		double totalSpaceM2 = 0;
		double totalSpaceM3 = 0;
		int totalNrOfTriangles = 0;
		
		long totalNrOfProperties = 0;
		long totalNrOfRelations = 0;
		long totalNrOfPsets = 0;
		int totalNrOfObjects = 0;

		for (EClass ifcProductClass : model.getPackageMetaData().getAllSubClasses(Ifc2x3tc1Package.eINSTANCE.getIfcProduct())) {
			int totalTypeTriangles = 0;
			List<IdEObject> products = model.getAll(ifcProductClass);
			int nrOfObjects = products.size();
			if (nrOfObjects == 0) {
				continue;
			}
			
			totalNrOfObjects += nrOfObjects;
			
			int totalNrOfTypeTriangles = 0;
			int totalNrOfTypePsets = 0;
			int totalNrOfTypeProperties = 0;
			int totalNrOfTypeRelations = 0;
			for (IdEObject product : products) {
				ObjectDetails objectDetails = new ObjectDetails(product);
				objects.add(objectDetails);
				GeometryInfo geometry = (GeometryInfo) product.eGet(product.eClass().getEStructuralFeature("geometry"));
				if (geometry != null) {
					totalNrOfTypeTriangles += geometry.getPrimitiveCount();
					totalNrOfTriangles += geometry.getPrimitiveCount();
					totalTypeTriangles += geometry.getPrimitiveCount();
					
					double volumeM3 = VolumeUnit.CUBIC_METER.convert(geometry.getVolume(), modelVolumeUnit);
					if (volumeM3 > 0.999 && volumeM3 < 1.001) {
						if (allVerticesWithinOneMeterOfZero(geometry, modelLengthUnit)) {
							cubesNearZero++;
						}
					}
					
					objectDetails.setNrTriangles(geometry.getPrimitiveCount());
					objectDetails.setVolume(DEFAULT_VOLUME_UNIT.convert(geometry.getVolume(), modelVolumeUnit));

					if (ifcProductClass.getName().equals("IfcSpace")) {
						totalSpaceM2 += DEFAULT_AREA_UNIT.convert(getArea(geometry), modelAreaUnit);
						totalSpaceM3 += DEFAULT_VOLUME_UNIT.convert(geometry.getVolume(), modelVolumeUnit);
					}
				}
				totalNrOfTypePsets += IfcUtils.getNrOfPSets(product, true);
				int nrOfProperties = IfcUtils.getNrOfProperties(product);
				totalNrOfTypeProperties += nrOfProperties;
				objectDetails.setNrOfProperties(nrOfProperties);
				totalNrOfTypeRelations += IfcUtils.getNrOfRelations(product);
			}
			ObjectNode productNode = OBJECT_MAPPER.createObjectNode();
			productNode.put("numberOfObjects", nrOfObjects);
			if (totalNrOfTypeTriangles > 0) {
				productNode.put("averageNumberOfTriangles", totalTypeTriangles / nrOfObjects);
			}
			productNode.put("averageNumberOfPsets", totalNrOfTypePsets / nrOfObjects);
			productNode.put("averageNumberOfProperties", totalNrOfTypeProperties / nrOfObjects);
			productNode.put("averageNumberOfRelations", totalNrOfTypeRelations / nrOfObjects);
			perType.set(ifcProductClass.getName(), productNode);
			
			totalNrOfProperties += totalNrOfTypeProperties;
			totalNrOfRelations += totalNrOfTypeRelations;
			totalNrOfPsets += totalNrOfTypePsets;
		}

		objects.sort(new Comparator<ObjectDetails>() {
			@Override
			public int compare(ObjectDetails o1, ObjectDetails o2) {
				return ((Float) o2.getTrianglesPerVolume()).compareTo(o1.getTrianglesPerVolume());
			}
		});

		ArrayNode topTenMostComplex = OBJECT_MAPPER.createArrayNode();
		for (int i = 0; i < 10 && i < objects.size(); i++) {
			ObjectDetails objectDetails = objects.get(i);
			ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
			objectNode.put("type", objectDetails.getProduct().eClass().getName());
			putNameAndGuid(objectNode, objectDetails.getProduct());
			if (objectDetails.getPrimitiveCount() != 0) {
				objectNode.put("numberOfTriangles", objectDetails.getPrimitiveCount());
			}
			if (objectDetails.getVolume() != 0) {
				objectNode.put("volumeM3", objectDetails.getVolume());
			}
			if (!Float.isNaN(objectDetails.getTrianglesPerVolume())) {
				objectNode.put("trianglesPerM3", objectDetails.getTrianglesPerVolume());
			}
			topTenMostComplex.add(objectNode);
		}
		aggregations.set("topTenMostComplexObjects", topTenMostComplex);

		objects.sort(new Comparator<ObjectDetails>() {
			@Override
			public int compare(ObjectDetails o1, ObjectDetails o2) {
				return o2.getNrOfProperties() - o1.getNrOfProperties();
			}
		});

		ArrayNode topTenMostProperties = OBJECT_MAPPER.createArrayNode();
		for (int i = 0; i < 10 && i < objects.size(); i++) {
			ObjectDetails objectDetails = objects.get(i);
			ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
			objectNode.put("type", objectDetails.getProduct().eClass().getName());
			putNameAndGuid(objectNode, objectDetails.getProduct());
			objectNode.put("numberOfProperties", objectDetails.getNrOfProperties());
			topTenMostProperties.add(objectNode);
		}
		aggregations.set("topTenMostProperties", topTenMostProperties);

		ObjectNode completeModel = OBJECT_MAPPER.createObjectNode();
		

		completeModel.put("totalTriangles", totalNrOfTriangles);
		completeModel.put("totalSpaceM2", totalSpaceM2);
		completeModel.put("totalSpaceM3", totalSpaceM3);
		
		completeModel.put("totalNrOfObjects", totalNrOfObjects);
		completeModel.put("totalNrOfProperties", totalNrOfProperties);
		completeModel.put("totalNrOfPsets", totalNrOfPsets);
		completeModel.put("totalNrOfRelations", totalNrOfRelations);

		if (totalSpaceM3 != 0) {
			double averageNrOfObjectsPerM3 = totalNrOfObjects / totalSpaceM3;
			if (Double.isFinite(averageNrOfObjectsPerM3)) {
				completeModel.put("averageNrOfObjectsPerM3", averageNrOfObjectsPerM3);
			}
			double averagem3 = totalNrOfTriangles / totalSpaceM3;
			if (Double.isFinite(averagem3)) {
				completeModel.put("averageAmountOfTrianglesPerM3", averagem3);
			}
		}
		if (totalNrOfObjects != 0) {
			double averageNrOfPropertiesPerObject = (double)totalNrOfProperties / totalNrOfObjects;
			if (Double.isFinite(averageNrOfPropertiesPerObject)) {
				completeModel.put("averageNrOfPropertiesPerObject", averageNrOfPropertiesPerObject);
			}
		}
		if (totalSpaceM2 != 0) {
			double averagem2 = totalNrOfTriangles / totalSpaceM2;
			if (Double.isFinite(averagem2)) {
				completeModel.put("averageAmountOfTrianglesPerM2", averagem2);
			}
		}

		aggregations.set("completeModel", completeModel);

		return aggregations;
	}

	private double getArea(GeometryInfo geometryInfo) {
		if (geometryInfo.getAdditionalData() != null) {
			try {
				ObjectNode additionalData = OBJECT_MAPPER.readValue(geometryInfo.getAdditionalData(), ObjectNode.class);
				if (additionalData.has("WALKABLE_SURFACE_AREA")) {
					return additionalData.get("WALKABLE_SURFACE_AREA").asDouble();
				} else if (additionalData.has("SURFACE_AREA_ALONG_Z")) {
					return additionalData.get("SURFACE_AREA_ALONG_Z").asDouble();
				}
			} catch (JsonParseException e) {
				e.printStackTrace();
			} catch (JsonMappingException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return geometryInfo.getArea();
	}
	
	private boolean allVerticesWithinOneMeterOfZero(GeometryInfo geometryInfo, LengthUnit modelLengthUnit) {
		boolean allVerticesWithin1Meter = true;
		if (geometryInfo.getData() != null && geometryInfo.getData().getVertices() != null && geometryInfo.getData().getVertices().getData() != null) {
			FloatBuffer vertices = ByteBuffer.wrap(geometryInfo.getData().getVertices().getData()).asFloatBuffer();
			for (int i=0; i<vertices.capacity(); i++) {
				float v = vertices.get(i);
				float meters = LengthUnit.METER.convert(v, modelLengthUnit);
				if (meters < -1 || meters > 1) {
					allVerticesWithin1Meter = false;
					break;
				}
			}
		} else {
			Bounds bounds = geometryInfo.getBoundsMm();
			double[] d = new double[] {
				bounds.getMin().getX(),
				bounds.getMin().getY(),
				bounds.getMin().getZ(),
				bounds.getMax().getX(),
				bounds.getMax().getY(),
				bounds.getMax().getZ()
			};
			for (int i=0; i<6; i++) {
				if (d[i] < -1000 || d[i] > 1000) {
					allVerticesWithin1Meter = false;
					break;
				}
			}
		}
		return allVerticesWithin1Meter;
	}

	private void putNameAndGuid(ObjectNode objectNode, IdEObject ifcRoot) {
		EStructuralFeature globalIdFeature = ifcRoot.eClass().getEStructuralFeature("GlobalId");
		EStructuralFeature nameFeature = ifcRoot.eClass().getEStructuralFeature("Name");
		String name = (String) ifcRoot.eGet(nameFeature);
		if (name != null) {
			objectNode.put("name", name);
		}
		if (globalIdFeature != null) {
			String guid = (String) ifcRoot.eGet(globalIdFeature);
			if (guid != null) {
				objectNode.put("guid", guid);
			}
		}
	}

	private JsonNode processClassification(IfcModelInterface model) {
		ArrayNode classificationsNode = OBJECT_MAPPER.createArrayNode();

		Map<Long, ObjectNode> classificationMap = new HashMap<>();
		Map<String, ObjectNode> classificationMapByString = new HashMap<>();
		Map<String, ObjectNode> classificationReferenceMapByString = new HashMap<>();

		for (IfcClassification ifcClassification : model.getAll(IfcClassification.class)) {
			String canonicalName = ifcClassification.getName() + "_" + ifcClassification.getEdition() + "_" + ifcClassification.getSource();
			ObjectNode classificationNode = OBJECT_MAPPER.createObjectNode();
			if (ifcClassification.getName() != null) {
				classificationNode.put("name", ifcClassification.getName());
			}
			if (ifcClassification.getEdition() != null) {
				classificationNode.put("edition", ifcClassification.getEdition());
			}
			if (ifcClassification.getSource() != null) {
				classificationNode.put("source", ifcClassification.getSource());
			}
			IfcCalendarDate editionDate = ifcClassification.getEditionDate();
			if (editionDate != null) {
				ObjectNode editionDateNode = OBJECT_MAPPER.createObjectNode();
				editionDateNode.put("yearComponent", editionDate.getYearComponent());
				editionDateNode.put("monthComponent", editionDate.getMonthComponent());
				editionDateNode.put("dayComponent", editionDate.getDayComponent());

				canonicalName += editionDate.getYearComponent() + "_" + editionDate.getMonthComponent() + "_" + editionDate.getDayComponent();

				classificationNode.set("editionDate", editionDateNode);
			}

			ArrayNode referencesNode = OBJECT_MAPPER.createArrayNode();
			classificationNode.set("references", referencesNode);

			if (classificationMapByString.containsKey(canonicalName)) {
				classificationNode = classificationMapByString.get(canonicalName);
			} else {
				classificationMapByString.put(canonicalName, classificationNode);
				classificationsNode.add(classificationNode);
			}
			classificationMap.put(ifcClassification.getOid(), classificationNode);
		}

		ObjectNode noClassification = OBJECT_MAPPER.createObjectNode();
		noClassification.put("name", "NO_CLASSIFICATION");
		noClassification.set("references", OBJECT_MAPPER.createArrayNode());
		classificationsNode.add(noClassification);

		ObjectNode noClassificationReference = OBJECT_MAPPER.createObjectNode();
		noClassificationReference.put("name", "NO_CLASSIFICATION_REFERENCE");
		noClassificationReference.put("numberOfObjects", 0);
		((ArrayNode)noClassification.get("references")).add(noClassificationReference);
		
		Map<Long, ObjectNode> classificationReferences = new HashMap<>();

		for (IfcClassificationReference ifcClassificationReference : model.getAllWithSubTypes(IfcClassificationReference.class)) {
			IfcClassification referencedSource = ifcClassificationReference.getReferencedSource();
			ObjectNode classificationNode = null;
			if (referencedSource == null) {
				classificationNode = noClassification;
			} else {
				classificationNode = classificationMap.get(referencedSource.getOid());
			}

			String canonicalName = ifcClassificationReference.getLocation() + "_" + ifcClassificationReference.getItemReference() + "_" + ifcClassificationReference.getName();
			ObjectNode classificationReferenceNode = null;
			if (classificationReferenceMapByString.containsKey(canonicalName)) {
				classificationReferenceNode = classificationReferenceMapByString.get(canonicalName);
			} else {
				classificationReferenceNode = OBJECT_MAPPER.createObjectNode();
				if (ifcClassificationReference.getLocation() != null) {
					classificationReferenceNode.put("location", ifcClassificationReference.getLocation());
				}
				if (ifcClassificationReference.getItemReference() != null) {
					classificationReferenceNode.put("itemReference", ifcClassificationReference.getItemReference());
				}
				if (ifcClassificationReference.getName() != null) {
					classificationReferenceNode.put("name", ifcClassificationReference.getName());
				}
				classificationReferenceNode.put("numberOfObjects", 0);
				classificationReferenceMapByString.put(canonicalName, classificationReferenceNode);
				((ArrayNode) classificationNode.get("references")).add(classificationReferenceNode);
			}

			classificationReferences.put(ifcClassificationReference.getOid(), classificationReferenceNode);
		}
		
		Set<Long> set = new HashSet<>();

		for (IfcRelAssociatesClassification ifcRelAssociatesClassification : model.getAll(IfcRelAssociatesClassification.class)) {
			IfcClassificationNotationSelect relatingClassification = ifcRelAssociatesClassification.getRelatingClassification();
			if (relatingClassification instanceof IfcClassificationReference) {
				IfcClassificationReference ifcClassificationReference = (IfcClassificationReference) relatingClassification;
				ObjectNode classificationReferenceNode = classificationReferences.get(ifcClassificationReference.getOid());
				EList<IfcRoot> relatedObjects = ifcRelAssociatesClassification.getRelatedObjects();
				for (IfcRoot ifcRoot : relatedObjects) {
					set.add(ifcRoot.getOid());
				}
				classificationReferenceNode.put("numberOfObjects", classificationReferenceNode.get("numberOfObjects").asInt() + relatedObjects.size());
			}
		}
		
		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			if (!set.contains(ifcProduct.getOid())) {
				noClassificationReference.put("numberOfObjects", noClassificationReference.get("numberOfObjects").asInt() + 1);
			}
		}

		return classificationsNode;
	}

	private JsonNode processMaterials(IfcModelInterface model) {
		ArrayNode materialsNode = OBJECT_MAPPER.createArrayNode();

		Map<Long, ObjectNode> materialNodes = new HashMap<>();

		for (IfcMaterial ifcMaterial : model.getAll(IfcMaterial.class)) {
			ObjectNode materialNode = OBJECT_MAPPER.createObjectNode();
			putNameAndGuid(materialNode, ifcMaterial);
			materialNode.put("nrOfProducts", 0);
			materialNodes.put(ifcMaterial.getOid(), materialNode);
			materialsNode.add(materialNode);
		}

		Set<Long> objectsWithMaterial = new HashSet<>();

		for (IfcRelAssociatesMaterial ifcRelAssociatesMaterial : model.getAll(IfcRelAssociatesMaterial.class)) {
			EList<IfcRoot> objects = ifcRelAssociatesMaterial.getRelatedObjects();
			for (IfcRoot object : objects) {
				objectsWithMaterial.add(object.getOid());
			}
			IfcMaterialSelect relatingMaterial = ifcRelAssociatesMaterial.getRelatingMaterial();
			if (relatingMaterial instanceof IfcMaterial) {
				ObjectNode materialNode = materialNodes.get(relatingMaterial.getOid());
				materialNode.put("nrOfProducts", materialNode.get("nrOfProducts").asInt() + objects.size());
			} else if (relatingMaterial instanceof IfcMaterialLayerSetUsage) {
				IfcMaterialLayerSetUsage ifcMaterialLayerSetUsage = (IfcMaterialLayerSetUsage) relatingMaterial;
				IfcMaterialLayerSet forLayerSet = ifcMaterialLayerSetUsage.getForLayerSet();
				for (IfcMaterialLayer ifcMaterialLayer : forLayerSet.getMaterialLayers()) {
					IfcMaterial ifcMaterial = ifcMaterialLayer.getMaterial();
					ObjectNode materialNode = materialNodes.get(ifcMaterial.getOid());
					materialNode.put("nrOfProducts", materialNode.get("nrOfProducts").asInt() + objects.size());
				}
			} else {
//				LOGGER.info("To implement: " + relatingMaterial);
			}
		}

		ObjectNode noMaterialNode = OBJECT_MAPPER.createObjectNode();
		noMaterialNode.put("name", "NO_MATERIAL");
		materialsNode.add(noMaterialNode);
		int objectsWithoutMaterial = 0;
		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			if (!objectsWithMaterial.contains(ifcProduct.getOid())) {
				objectsWithoutMaterial++;
			}
		}
		noMaterialNode.put("nrOfProducts", objectsWithoutMaterial);

		return materialsNode;
	}

	private JsonNode processProject(IfcModelInterface model) {
		IdEObject ifcProject = model.getFirst(model.getPackageMetaData().getEClass("IfcProject"));
		if (ifcProject == null) {
			return null;
		}
		ObjectNode projectNode = OBJECT_MAPPER.createObjectNode();
		putNameAndGuid(projectNode, ifcProject);
		IdEObject unitInContext = (IdEObject) ifcProject.eGet(ifcProject.eClass().getEStructuralFeature("UnitsInContext"));
		
		if (unitInContext != null) {
			ArrayNode unitsNode = OBJECT_MAPPER.createArrayNode();
			List<IdEObject> units = (List<IdEObject>) unitInContext.eGet(unitInContext.eClass().getEStructuralFeature("Units"));
			for (IdEObject unit : units) {
				ObjectNode unitNode = OBJECT_MAPPER.createObjectNode();
				EStructuralFeature unitTypeFeature = unit.eClass().getEStructuralFeature("UnitType");
				if (unitTypeFeature != null) {
					Object unitType = unit.eGet(unitTypeFeature);
					if (unitType != null) {
						unitNode.put("unitType", unitType.toString());
					}
				}
				EStructuralFeature nameFeature = unit.eClass().getEStructuralFeature("Name");
				if (nameFeature != null) {
					Object name = unit.eGet(nameFeature);
					if (name != null) {
						unitNode.put("name", name.toString());
					}
				}
				EStructuralFeature prefixFeature = unit.eClass().getEStructuralFeature("Prefix");
				if (prefixFeature != null) {
					Object prefix = unit.eGet(prefixFeature);
					if (prefix != null) {
						unitNode.put("prefix", prefix.toString());
					}
				}
				unitsNode.add(unitNode);
			}
			projectNode.set("units", unitsNode);
		}

		List<IdEObject> isDecomposedBy = (List<IdEObject>) ifcProject.eGet(ifcProject.eClass().getEStructuralFeature("IsDecomposedBy"));
		ArrayNode sitesNode = OBJECT_MAPPER.createArrayNode();
		for (IdEObject ifcRelDecomposes : isDecomposedBy) {
			List<IdEObject> sites = (List<IdEObject>) ifcRelDecomposes.eGet(ifcRelDecomposes.eClass().getEStructuralFeature("RelatedObjects"));
			for (IdEObject ifcSite : sites) {
				sitesNode.add(processSite(model, ifcSite));
			}
		}
		projectNode.set("sites", sitesNode);
		return projectNode;
	}

	private ObjectNode processBuilding(IfcModelInterface model, IfcBuilding ifcBuilding) {
		IfcPostalAddress buildingAddress = ifcBuilding.getBuildingAddress();
		if (buildingAddress != null) {

		}
		ObjectNode buildingNode = OBJECT_MAPPER.createObjectNode();

		putNameAndGuid(buildingNode, ifcBuilding);

		ArrayNode storeysNode = OBJECT_MAPPER.createArrayNode();
		buildingNode.set("storeys", storeysNode);

		for (IfcRelDecomposes ifcRelDecomposes : ifcBuilding.getIsDecomposedBy()) {
			for (IfcObjectDefinition ifcObjectDefinition : ifcRelDecomposes.getRelatedObjects()) {
				if (ifcObjectDefinition instanceof IfcBuildingStorey) {
					IfcBuildingStorey ifcBuildingStorey = (IfcBuildingStorey) ifcObjectDefinition;
					int numberOfObjectsInStorey = IfcUtils.countDecomposed(ifcBuildingStorey);

					ObjectNode buildingStoreyNode = OBJECT_MAPPER.createObjectNode();
					putNameAndGuid(buildingStoreyNode, ifcBuildingStorey);
					buildingStoreyNode.put("totalNumberOfObjects", numberOfObjectsInStorey);

					ArrayNode spacesNode = OBJECT_MAPPER.createArrayNode();
					for (IfcRelDecomposes ifcRelDecomposes2 : ifcBuildingStorey.getIsDecomposedBy()) {
						for (IfcObjectDefinition ifcObjectDefinition2 : ifcRelDecomposes2.getRelatedObjects()) {
							if (ifcObjectDefinition2 instanceof IfcSpace) {
								IfcSpace ifcSpace = (IfcSpace) ifcObjectDefinition2;
								ObjectNode spaceNode = OBJECT_MAPPER.createObjectNode();
								putNameAndGuid(spaceNode, ifcSpace);
								GeometryInfo spaceGeometry = ifcSpace.getGeometry();
								if (spaceGeometry != null) {
									spaceNode.put("m2", DEFAULT_AREA_UNIT.convert(getArea(spaceGeometry), modelAreaUnit));
									spaceNode.put("m3", DEFAULT_VOLUME_UNIT.convert(spaceGeometry.getVolume(), modelVolumeUnit));
								}
								ArrayNode zonesNode = OBJECT_MAPPER.createArrayNode();
								for (IfcRelAssigns ifcRelAssigns : ifcSpace.getHasAssignments()) {
									if (ifcRelAssigns instanceof IfcRelAssignsToGroup) {
										IfcRelAssignsToGroup ifcRelAssignsToGroup = (IfcRelAssignsToGroup) ifcRelAssigns;
										IfcGroup relatingGroup = ifcRelAssignsToGroup.getRelatingGroup();
										if (relatingGroup instanceof IfcZone) {
											IfcZone ifcZone = (IfcZone) relatingGroup;
											ObjectNode zoneNode = OBJECT_MAPPER.createObjectNode();
											putNameAndGuid(zoneNode, ifcZone);
											zonesNode.add(zoneNode);
										}
									}
								}
								if (zonesNode.size() > 0) {
									spaceNode.set("zones", zonesNode);
								}
								spacesNode.add(spaceNode);
							}
						}
					}
					buildingStoreyNode.set("spaces", spacesNode);

					storeysNode.add(buildingStoreyNode);
				}
			}
		}
		return buildingNode;
	}

	private JsonNode processSite(IfcModelInterface model, IdEObject ifcSite) {
		ObjectNode siteNode = OBJECT_MAPPER.createObjectNode();
		putNameAndGuid(siteNode, ifcSite);

		ArrayNode buildingsNode = OBJECT_MAPPER.createArrayNode();
		List<IdEObject> isDecomposedBy = (List<IdEObject>) ifcSite.eGet(ifcSite.eClass().getEStructuralFeature("IsDecomposedBy"));
		for (IdEObject ifcRelDecomposes : isDecomposedBy) {
			List<IdEObject> relatedObjects = (List<IdEObject>) ifcRelDecomposes.eGet(ifcRelDecomposes.eClass().getEStructuralFeature("RelatedObjects"));
			for (IdEObject ifcBuilding : relatedObjects) {
				buildingsNode.add(processBuilding(model, (IfcBuilding) ifcBuilding));
			}
		}
		siteNode.set("buildings", buildingsNode);

		return siteNode;
	}

	private ObjectNode proccessIfcHeader(IfcHeader ifcHeader) {
		ObjectNode headerNode = OBJECT_MAPPER.createObjectNode();

		ArrayNode authorsNode = OBJECT_MAPPER.createArrayNode();
		headerNode.set("author", authorsNode);

		for (String author : ifcHeader.getAuthor()) {
			authorsNode.add(author);
		}

		headerNode.put("authorization", ifcHeader.getAuthorization());
		ArrayNode descriptionsNode = OBJECT_MAPPER.createArrayNode();
		for (String description : ifcHeader.getDescription()) {
			descriptionsNode.add(description);
		}
		headerNode.set("description", descriptionsNode);

		headerNode.put("filename", ifcHeader.getFilename());
		headerNode.put("schemaVersion", ifcHeader.getIfcSchemaVersion());
		headerNode.put("implementationLevel", ifcHeader.getImplementationLevel());

		ArrayNode organizationsNode = OBJECT_MAPPER.createArrayNode();
		for (String organization : ifcHeader.getOrganization()) {
			organizationsNode.add(organization);
		}
		headerNode.set("organization", organizationsNode);
		headerNode.put("originatingSystem", ifcHeader.getOriginatingSystem());
		headerNode.put("preProcessorVersion", ifcHeader.getPreProcessorVersion());
		headerNode.put("timeStamp", ifcHeader.getTimeStamp().getTime());

		return headerNode;
	}

	@Override
	public String getOutputSchema() {
		return "IFC_ANALYTICS_JSON_1_0";
	}

	@Override
	public boolean needsRawInput() {
		return true;
	}
	
	@Override
	public boolean requiresGeometry() {
		return true;
	}
}