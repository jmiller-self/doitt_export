package gov.nyc.doitt.service;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.geotools.data.DataStore;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.FeatureSource;
import org.geotools.data.Transaction;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.vividsolutions.jts.geom.Geometry;

@Service
public class ProcessShapefile {
	@Autowired
	private EmailService es;
	private final Logger log = LoggerFactory.getLogger(this.getClass());
	private final String fromto="fromTocl";
	private final String tofrom="toFromcl";
	public File processZipShape(File shapeZipIn){
		Path zipfile = null;
		File shpfile = null;
		try {
			 Path temppath = Files.createTempDirectory("bikepathtemp");
			 //Path temppath2 = Files.createTempDirectory("bikepathtemp2");
	         File shppath = temppath.toFile();
	         shpfile = new File(temppath.toString(), "bikepath.shp");
	         //zipfile = Files.createTempFile(temppath2, "bp", ".zip");
			 FeatureCollection<SimpleFeatureType, SimpleFeature> existing = getExistingFeatureCollection(shapeZipIn);

			SimpleFeatureStore output = getOutputDataStore(shpfile.toURI().toURL(),existing.getSchema(),existing.features());
			
			//FileOutputStream fos = new FileOutputStream(zipfile.toString());
           // ZipOutputStream zip = new ZipOutputStream(fos);
            //zipDirectory(shppath, zip);
           // zip.close();
            System.out.println(shppath);
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			log.error(e.getLocalizedMessage());
			e.printStackTrace();
			es.send(e.getLocalizedMessage());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			log.error(e.getLocalizedMessage());
			es.send(e.getLocalizedMessage());
		}
		//return zipfile.toFile();
		return shpfile;
			 
	}

	private FeatureCollection<SimpleFeatureType, SimpleFeature> getExistingFeatureCollection(File shapeZipIn) {
		final HashMap<String, Serializable> params = new HashMap<>(3);
		final ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
		FeatureCollection<SimpleFeatureType, SimpleFeature> out = null;
		try {
			URL unzippedShp = unzipShapeFile(shapeZipIn);
			params.put(ShapefileDataStoreFactory.URLP.key, unzippedShp);
			params.put(ShapefileDataStoreFactory.CREATE_SPATIAL_INDEX.key, Boolean.FALSE);
			params.put(ShapefileDataStoreFactory.ENABLE_SPATIAL_INDEX.key, Boolean.FALSE);
			ShapefileDataStore dataStore = (ShapefileDataStore) factory.createDataStore(params);
			String typeName = dataStore.getTypeNames()[0];
			FeatureSource<SimpleFeatureType, SimpleFeature> source= dataStore
			        .getFeatureSource(typeName);
			out = source.getFeatures();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			log.error(e.getLocalizedMessage());
			es.send(e.getLocalizedMessage());
		}


		return out;
	}
	
	private SimpleFeatureStore getOutputDataStore(URL outurl,SimpleFeatureType existingFeatureType, FeatureIterator<SimpleFeature>existingfeatures){
		final Transaction transaction = new DefaultTransaction("create");
		SimpleFeatureStore out =null;
		final HashMap<String, Serializable> params = new HashMap<>(3);
		final ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
		params.put(ShapefileDataStoreFactory.URLP.key, outurl);
		params.put(ShapefileDataStoreFactory.CREATE_SPATIAL_INDEX.key, Boolean.FALSE);
		params.put(ShapefileDataStoreFactory.ENABLE_SPATIAL_INDEX.key, Boolean.FALSE);
		try {
			ShapefileDataStore dataStore = (ShapefileDataStore) factory.createDataStore(params);
			SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
			builder.add(fromto, String.class);
			builder.add(tofrom, String.class);
            CoordinateReferenceSystem worldCRS = getTargetCRS();
            CoordinateReferenceSystem dataCRS = existingFeatureType.getCoordinateReferenceSystem();
            SimpleFeatureType reprojFeatureType = SimpleFeatureTypeBuilder.retype(existingFeatureType, worldCRS);
            for (AttributeDescriptor descriptor : reprojFeatureType.getAttributeDescriptors()) {
            	if(!descriptor.getLocalName().equalsIgnoreCase("AllClasses"))
            		builder.add(descriptor);
            }

            builder.setName(reprojFeatureType.getName());
            builder.setCRS(reprojFeatureType.getCoordinateReferenceSystem());
            reprojFeatureType = builder.buildFeatureType();
			dataStore.createSchema(reprojFeatureType);
			final String typeName = dataStore.getTypeNames()[0];
            final SimpleFeatureSource featureSource = dataStore.getFeatureSource(typeName);
            if (!(featureSource instanceof SimpleFeatureStore)) {
                log.error("Could not create feature store.");
                es.send("Could not create feature store.");
            }
            out = (SimpleFeatureStore) featureSource;

            SimpleFeatureBuilder fbuilder = new SimpleFeatureBuilder(reprojFeatureType);
            boolean lenient = true;
            MathTransform transform = CRS.findMathTransform(dataCRS, worldCRS, lenient);
            List<SimpleFeature> features = new ArrayList<SimpleFeature>();
            while (existingfeatures.hasNext()) {
                SimpleFeature feature = existingfeatures.next();
                for (Property property : feature.getProperties()) {
                    if (property instanceof GeometryAttribute) {
                    	Geometry geometry = (Geometry) property.getValue();
                    	Geometry geometry2 = JTS.transform(geometry, transform);
                        fbuilder.set(existingFeatureType.getGeometryDescriptor().getName(),
                                geometry2);
                    } else {
                    	if(!property.getName().toString().equalsIgnoreCase("AllClasses"))
                    		fbuilder.set(property.getName(), property.getValue());
                    	else{
                    		String clazzfromto="";
                    		String clazztofrom="";
                    		String[]clazzes = ((String)property.getValue()).split(",");
                    		if(clazzes.length==1){
                    			clazzfromto=clazzes[0];
                    			clazztofrom=clazzes[0];
                    		}else if(clazzes.length==2){
                    			clazzfromto=clazzes[0];
                    			clazztofrom=clazzes[1];
                    		}
                    		fbuilder.set(fromto, clazzfromto);
                    		fbuilder.set(tofrom, clazztofrom);
                    			
                    	}
                    }
                }
                Feature modifiedFeature = fbuilder.buildFeature(feature.getIdentifier().getID());
                features.add((SimpleFeature) modifiedFeature);
            }
            SimpleFeatureCollection collection = new ListFeatureCollection(reprojFeatureType, features);
            try {
                out.addFeatures(collection);
                transaction.commit();
            } catch (Exception problem) {
                problem.printStackTrace();
                transaction.rollback();
            } finally {
                transaction.close();
                existingfeatures.close();
            }

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			log.error(e.getLocalizedMessage());
			es.send(e.getLocalizedMessage());
		} catch (FactoryException e) {
			e.printStackTrace();
			log.error(e.getLocalizedMessage());
			es.send(e.getLocalizedMessage());
		} catch (MismatchedDimensionException e) {
			e.printStackTrace();
			log.error(e.getLocalizedMessage());
			es.send(e.getLocalizedMessage());
		} catch (TransformException e) {
			e.printStackTrace();
			log.error(e.getLocalizedMessage());
			es.send(e.getLocalizedMessage());
		}
		return out;
	}
	
	 private CoordinateReferenceSystem getTargetCRS() {
		 CoordinateReferenceSystem crsout = null;
		try {
			crsout=CRS.decode("EPSG:4326");
		} catch (NoSuchAuthorityCodeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (FactoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return crsout;
	}

	private URL unzipShapeFile(File zipFile) throws IOException {
         URL out = null;
         byte[] buffer = new byte[1024];
         ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
         ZipEntry ze = zis.getNextEntry();
         while (ze != null) {

             String fileName = ze.getName();
             File newFile = new File(zipFile.getParent() + File.separator + fileName);

             if ("shp".equalsIgnoreCase(com.google.common.io.Files.getFileExtension(newFile
                     .getAbsolutePath()))) {
                 out = newFile.toURI().toURL();
             }

             System.out.println("file unzip : " + newFile.getAbsoluteFile());

             // create all non exists folders
             // else you will hit FileNotFoundException for compressed folder
             new File(newFile.getParent()).mkdirs();

             FileOutputStream fos = new FileOutputStream(newFile);

             int len;
             while ((len = zis.read(buffer)) > 0) {
                 fos.write(buffer, 0, len);
             }

             fos.close();
             ze = zis.getNextEntry();
         }

         zis.closeEntry();
         zis.close();

         return out;

     }


	private SimpleFeatureType addChangeTypeAttribute(SimpleFeatureType featureType) {
	    SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
	    builder.add("placeholder", String.class);
	    for (AttributeDescriptor descriptor : featureType.getAttributeDescriptors()) {
	        builder.add(descriptor);
	    }
	    builder.setName(featureType.getName());
	    builder.setCRS(featureType.getCoordinateReferenceSystem());
	    featureType = builder.buildFeatureType();
	    return featureType;
	}
	

}
