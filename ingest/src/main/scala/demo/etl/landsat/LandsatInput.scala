package demo.etl.landsat

import geotrellis.proj4.{CRS, LatLng, WebMercator}
import geotrellis.raster.split.Split
import geotrellis.raster._
import geotrellis.spark._
import geotrellis.spark.etl.{EtlJob, InputPlugin}
import geotrellis.spark.tiling._

import com.azavea.landsatutil.LandsatImage
import com.github.nscala_time.time.Imports._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.util.Try

abstract class LandsatInput[I, V] extends InputPlugin[I, V] {
  val name = "landsat"

  var images: Seq[LandsatImage] = Seq()

  def fetchMethod: (LandsatImage, EtlJob) => Option[ProjectedRaster[MultibandTile]] = { (img, job) =>
    Try { img.getRasterFromS3(bandsWanted = job.bandsWanted, hook = job.cacheHook) }
      .recover{ case err => img.getFromGoogle(bandsWanted = job.bandsWanted, hook = job.cacheHook).raster }
      .toOption
  }

  /** Calculate the layer metadata for the incoming landsat images
    *
    * Normally we would have no information about the incoming rasters and we be forced
    * to use [[TileLayerMetadata.fromRdd]] to collect it before we could tile the imagery.
    * But in this case the pre-query from scala-landsatutil is providing enough
    * information that the metadata can be calculated.
    *
    * Collecting metadata before tiling step requires either reading the data twice
    * or caching records in spark memory. In either case avoiding collection is a performance boost.
    */

  def calculateTileLayerMetadata(maxZoom: Int = 13, destCRS: CRS = WebMercator) = {
    val layoutDefinition = ZoomedLayoutScheme.layoutForZoom(maxZoom, destCRS.worldExtent, 256)
    val imageExtent = images.map(_.footprint.envelope).reduce(_ combine _).reproject(LatLng, destCRS)
    val dateMin = images.map(_.aquisitionDate).min
    val dateMax = images.map(_.aquisitionDate).max
    val GridBounds(colMin, rowMin, colMax, rowMax) = layoutDefinition.mapTransform(imageExtent)
    TileLayerMetadata(
      cellType = UShortCellType,
      layout = layoutDefinition,
      extent = imageExtent,
      crs = destCRS,
      bounds = KeyBounds(
        SpaceTimeKey(colMin, rowMin, dateMin),
        SpaceTimeKey(colMax, rowMax, dateMax))
    )
  }

  /** Transforms a collection of Landsat image descriptions into RDD of MultibandTiles.
    * Each landsat scene is downloaded, reprojected and then split into 256x256 chunks.
    * Chunking the scene allows for greater parallism and reduces memory pressure
    * produces by processing each partition.
    */
  def fetch(
    job: EtlJob,
    images: Seq[LandsatImage],
    source: (LandsatImage, EtlJob) => Option[ProjectedRaster[MultibandTile]]
  )(implicit sc: SparkContext): RDD[(TemporalProjectedExtent, MultibandTile)] = {
    sc.parallelize(images, images.length) // each image gets its own partition
      .mapPartitions({ iter =>
      for {
        img <- iter
        ProjectedRaster(raster, crs) <- source(img, job).toList
        reprojected = raster.reproject(crs, WebMercator) // reprojection before chunking avoids NoData artifacts
        layoutCols = math.ceil(reprojected.cols.toDouble / 256).toInt
        layoutRows = math.ceil(reprojected.rows.toDouble / 256).toInt
        chunk <- reprojected.split(TileLayout(layoutCols, layoutRows, 256, 256), Split.Options(cropped = false, extend = false))
      } yield {
        TemporalProjectedExtent(chunk.extent, WebMercator, img.aquisitionDate) -> chunk.tile
      }
    }, preservesPartitioning = true)
      .repartition(images.length * 16) // Break up each scene into 16 partitions
  }
}