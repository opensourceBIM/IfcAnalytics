package org.opensourcebim.ifcanalytics;

import java.io.IOException;
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
import org.bimserver.interfaces.objects.SObjectType;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.Ifc2x3tc1Package;
import org.bimserver.models.ifc2x3tc1.IfcBuilding;
import org.bimserver.models.ifc2x3tc1.IfcBuildingStorey;
import org.bimserver.models.ifc2x3tc1.IfcCalendarDate;
import org.bimserver.models.ifc2x3tc1.IfcClassification;
import org.bimserver.models.ifc2x3tc1.IfcClassificationNotation;
import org.bimserver.models.ifc2x3tc1.IfcClassificationNotationSelect;
import org.bimserver.models.ifc2x3tc1.IfcClassificationReference;
import org.bimserver.models.ifc2x3tc1.IfcGroup;
import org.bimserver.models.ifc2x3tc1.IfcMaterial;
import org.bimserver.models.ifc2x3tc1.IfcMaterialDefinitionRepresentation;
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
import org.bimserver.models.ifc2x3tc1.IfcRepresentation;
import org.bimserver.models.ifc2x3tc1.IfcRoot;
import org.bimserver.models.ifc2x3tc1.IfcSpace;
import org.bimserver.models.ifc2x3tc1.IfcZone;
import org.bimserver.models.store.IfcHeader;
import org.bimserver.plugins.services.BimBotAbstractService;
import org.bimserver.plugins.services.BimBotCaller;
import org.bimserver.plugins.services.BimBotExecutionException;
import org.bimserver.utils.IfcUtils;
import org.eclipse.aether.impl.OfflineController;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;

public class IfcAnalyticsService extends BimBotAbstractService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Logger LOGGER = LoggerFactory.getLogger(IfcAnalyticsService.class);
	
	// TODO convert to consistent length/volume units
	// TODO hasCubeNearZero
	
	@Override
	public BimBotsOutput runBimBot(BimBotsInput input, BimBotContext bimBotContext, SObjectType settings) throws BimBotsException {
		IfcModelInterface model = input.getIfcModel();
		
		ObjectNode result = OBJECT_MAPPER.createObjectNode();
		result.set("header", proccessIfcHeader(model.getModelMetaData().getIfcHeader()));
		result.set("buildings", processBuildings(model));
		result.set("materials", processMaterials(model));
		result.set("classifications", processClassification(model));
		result.set("aggregations", processAggregations(model));
		result.set("checks", processChecks(input));
		
		BimBotsOutput bimBotsOutput = new BimBotsOutput("IFC_ANALYTICS_JSON_1_0", result.toString().getBytes(Charsets.UTF_8));
		bimBotsOutput.setTitle("Ifc Analytics Results");
		bimBotsOutput.setContentType("application/json");
		
		return bimBotsOutput;
	}
	
	private ArrayNode callClashDetectionService(BimBotsInput input) {
		try (BimBotCaller bimBotCaller = new BimBotCaller("http://localhost:8080/services", "token")) {
			BimBotsOutput bimBotsOutput = bimBotCaller.call("3407950", input);
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
	
	private JsonNode processChecks(BimBotsInput input) {
		ObjectNode checksNode = OBJECT_MAPPER.createObjectNode();
//		checksNode.set("clashes", callClashDetectionService(input));
		checksNode.put("hasCubeNearZero", false);
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
		
		for (EClass ifcProductClass : model.getPackageMetaData().getAllSubClasses(Ifc2x3tc1Package.eINSTANCE.getIfcProduct())) {
			List<IdEObject> products = model.getAll(ifcProductClass);
			int nrOfObjects = products.size();
			if (nrOfObjects == 0) {
				continue;
			}
			int totalNrOfTypeTriangles = 0;
			int totalNrOfPsets = 0;
			int totalNrOfProperties = 0;
			int totalNrOfRelations = 0;
			for (IdEObject product : products) {
				ObjectDetails objectDetails = new ObjectDetails(product);
				objects.add(objectDetails);
				GeometryInfo geometry = (GeometryInfo) product.eGet(product.eClass().getEStructuralFeature("geometry"));
				if (geometry != null) {
					totalNrOfTypeTriangles += geometry.getPrimitiveCount();
					totalNrOfTriangles += geometry.getPrimitiveCount();
					objectDetails.setNrTriangles(geometry.getPrimitiveCount());
					objectDetails.setVolume(geometry.getVolume());
					
					if (ifcProductClass.getName().equals("IfcSpace")) {
						totalSpaceM2 += geometry.getArea();
						totalSpaceM3 += geometry.getVolume();
					}
				}
				totalNrOfPsets += IfcUtils.getNrOfPSets(product);
				int nrOfProperties = IfcUtils.getNrOfProperties(product);
				objectDetails.setNrOfProperties(nrOfProperties);
				totalNrOfPsets += nrOfProperties;
				totalNrOfPsets += IfcUtils.getNrOfRelations(product);
			}
			ObjectNode productNode = OBJECT_MAPPER.createObjectNode();
			productNode.put("numberOfObjects", nrOfObjects);
			if (totalNrOfTypeTriangles > 0) {
				productNode.put("averageNumberOfTriangles", totalNrOfTriangles / nrOfObjects);
			}
			productNode.put("averageNumberOfPsets", totalNrOfPsets / nrOfObjects);
			productNode.put("averageNumberOfProperties", totalNrOfProperties / nrOfObjects);
			productNode.put("averageNumberOfRelations", totalNrOfRelations / nrOfObjects);
			perType.set(ifcProductClass.getName(), productNode);
		}
		
		objects.sort(new Comparator<ObjectDetails>() {
			@Override
			public int compare(ObjectDetails o1, ObjectDetails o2) {
				return ((Float)o2.getTrianglesPerVolume()).compareTo(o1.getTrianglesPerVolume());
			}
		});
		
		ArrayNode topTenMostComplex = OBJECT_MAPPER.createArrayNode();
		for (int i=0; i<10 && i<objects.size(); i++) {
			ObjectDetails objectDetails = objects.get(i);
			ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
			objectNode.put("type", objectDetails.getProduct().eClass().getName());
			putNameAndGuid(objectNode, objectDetails.getProduct());
			if (objectDetails.getPrimitiveCount() != 0) {
				objectNode.put("numberOfTriangles", objectDetails.getPrimitiveCount());
			}
			if (objectDetails.getVolume() != 0) {
				objectNode.put("volume", objectDetails.getVolume());
			}
			if (!Float.isNaN(objectDetails.getTrianglesPerVolume())) {
				objectNode.put("trianglesPerVolume", objectDetails.getTrianglesPerVolume());
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
		for (int i=0; i<10 && i<objects.size(); i++) {
			ObjectDetails objectDetails = objects.get(i);
			ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
			objectNode.put("type", objectDetails.getProduct().eClass().getName());
			putNameAndGuid(objectNode, objectDetails.getProduct());
			objectNode.put("numberOfProperties", objectDetails.getNrOfProperties());
			topTenMostProperties.add(objectNode);
		}
		aggregations.set("topTenMostProperties", topTenMostProperties);
		
		ObjectNode completeModel = OBJECT_MAPPER.createObjectNode();
		completeModel.put("averageAmountOfTrianglesPerM2", totalNrOfTriangles / totalSpaceM2);
		completeModel.put("averageAmountOfTrianglesPerM3", totalNrOfTriangles / totalSpaceM3);
		
		aggregations.set("completeModel", completeModel);

		return aggregations;
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
			}
			classificationsNode.add(classificationNode);
			classificationMap.put(ifcClassification.getOid(), classificationNode);
		}
		
		ObjectNode noClassification = OBJECT_MAPPER.createObjectNode();
		noClassification.put("name", "NO_CLASSIFICATION");
		noClassification.set("references", OBJECT_MAPPER.createArrayNode());
		classificationsNode.add(noClassification);
		
		Map<Long, ObjectNode> classificationReferences = new HashMap<>();
		
		for (IfcClassificationReference ifcClassificationReference : model.getAllWithSubTypes(IfcClassificationReference.class)) {
			ObjectNode classificationReferenceNode = OBJECT_MAPPER.createObjectNode();
			if (ifcClassificationReference.getLocation() != null) {
				classificationReferenceNode.put("location", ifcClassificationReference.getLocation());
			}
			if (ifcClassificationReference.getItemReference() != null) {
				classificationReferenceNode.put("itemReference", ifcClassificationReference.getItemReference());
			}
			if (ifcClassificationReference.getName() != null) {
				classificationReferenceNode.put("name", ifcClassificationReference.getName());
			}
			
			IfcClassification referencedSource = ifcClassificationReference.getReferencedSource();
			ObjectNode classificationNode = null;
			if (referencedSource == null) {
				classificationNode = noClassification;
			} else {
				classificationNode = classificationMap.get(referencedSource.getOid());
			}
			classificationReferences.put(ifcClassificationReference.getOid(), classificationReferenceNode);
			((ArrayNode)classificationNode.get("references")).add(classificationReferenceNode);
			classificationReferenceNode.put("numberOfObjects", 0);
		}
		
		for (IfcRelAssociatesClassification ifcRelAssociatesClassification : model.getAll(IfcRelAssociatesClassification.class)) {
			ifcRelAssociatesClassification.getRelatedObjects().size();
			IfcClassificationNotationSelect relatingClassification = ifcRelAssociatesClassification.getRelatingClassification();
			if (relatingClassification instanceof IfcClassificationReference) {
				IfcClassificationReference ifcClassificationReference = (IfcClassificationReference)relatingClassification;
				ObjectNode classificationReferenceNode = classificationReferences.get(ifcClassificationReference.getOid());
				classificationReferenceNode.put("numberOfObjects", classificationReferenceNode.get("numberOfObjects").asInt() + 1);
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
				IfcMaterialLayerSetUsage ifcMaterialLayerSetUsage = (IfcMaterialLayerSetUsage)relatingMaterial;
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

	private JsonNode processBuildings(IfcModelInterface model) {
		ArrayNode buildingsNode = OBJECT_MAPPER.createArrayNode();
		
		for (IfcBuilding ifcBuilding : model.getAll(IfcBuilding.class)) {
			IfcPostalAddress buildingAddress = ifcBuilding.getBuildingAddress();
			if (buildingAddress != null) {
				
			}
			ObjectNode buildingNode = OBJECT_MAPPER.createObjectNode();
			buildingsNode.add(buildingNode);
			
			putNameAndGuid(buildingNode, ifcBuilding);

			ArrayNode storeysNode = OBJECT_MAPPER.createArrayNode();
			buildingNode.set("storeys", storeysNode);
			
			for (IfcRelDecomposes ifcRelDecomposes : ifcBuilding.getIsDecomposedBy()) {
				for (IfcObjectDefinition ifcObjectDefinition : ifcRelDecomposes.getRelatedObjects()) {
					if (ifcObjectDefinition instanceof IfcBuildingStorey) {
						IfcBuildingStorey ifcBuildingStorey = (IfcBuildingStorey)ifcObjectDefinition;
						int numberOfObjectsInStorey = IfcUtils.countDecomposed(ifcBuildingStorey);
						
						ObjectNode buildingStoreyNode = OBJECT_MAPPER.createObjectNode();
						buildingStoreyNode.put("name", ifcBuildingStorey.getName());
						buildingStoreyNode.put("guid", ifcBuildingStorey.getGlobalId());
						buildingStoreyNode.put("totalNumberOfObjects", numberOfObjectsInStorey);
						
						ArrayNode spacesNode = OBJECT_MAPPER.createArrayNode();
						for (IfcRelDecomposes ifcRelDecomposes2 : ifcBuildingStorey.getIsDecomposedBy()) {
							for (IfcObjectDefinition ifcObjectDefinition2 : ifcRelDecomposes2.getRelatedObjects()) {
								if (ifcObjectDefinition2 instanceof IfcSpace) {
									IfcSpace ifcSpace = (IfcSpace)ifcObjectDefinition2;
									ObjectNode spaceNode = OBJECT_MAPPER.createObjectNode();
									putNameAndGuid(spaceNode, ifcSpace);
									GeometryInfo spaceGeometry = ifcSpace.getGeometry();
									if (spaceGeometry != null) {
										spaceNode.put("m2", spaceGeometry.getArea());
										spaceNode.put("m3", spaceGeometry.getVolume());
									}
									ArrayNode zonesNode = OBJECT_MAPPER.createArrayNode();
									for (IfcRelAssigns ifcRelAssigns : ifcSpace.getHasAssignments()) {
										if (ifcRelAssigns instanceof IfcRelAssignsToGroup) {
											IfcRelAssignsToGroup ifcRelAssignsToGroup = (IfcRelAssignsToGroup)ifcRelAssigns;
											IfcGroup relatingGroup = ifcRelAssignsToGroup.getRelatingGroup();
											if (relatingGroup instanceof IfcZone) {
												IfcZone ifcZone = (IfcZone)relatingGroup;
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
		}
		
		return buildingsNode;
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
}
