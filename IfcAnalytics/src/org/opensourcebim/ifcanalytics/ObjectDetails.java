package org.opensourcebim.ifcanalytics;

import org.bimserver.emf.IdEObject;

public class ObjectDetails {

	private IdEObject product;
	private int primitiveCount;
	private double volume;
	private int nrOfProperties;

	public ObjectDetails(IdEObject product) {
		this.product = product;
	}

	public void setNrTriangles(int primitiveCount) {
		this.primitiveCount = primitiveCount;
	}

	public void setVolume(double volume) {
		this.volume = volume;
	}
	
	public double getVolume() {
		return volume;
	}
	
	public int getPrimitiveCount() {
		return primitiveCount;
	}
	
	public IdEObject getProduct() {
		return product;
	}
	
	public float getTrianglesPerVolume() {
		if (volume == 0 || primitiveCount == 0) {
			return 0;
		}
		return (float) (primitiveCount / volume);
	}

	public void setNrOfProperties(int nrOfProperties) {
		this.nrOfProperties = nrOfProperties;
	}
	
	public int getNrOfProperties() {
		return nrOfProperties;
	}
}
