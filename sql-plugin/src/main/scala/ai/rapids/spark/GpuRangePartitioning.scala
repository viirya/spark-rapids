/*
 * Copyright (c) 2020, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.rapids.spark

import ai.rapids.cudf.{ColumnVector, Table}
import ai.rapids.spark.RapidsPluginImplicits._

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.plans.physical.{ClusteredDistribution, Distribution, OrderedDistribution}
import org.apache.spark.sql.types.{DataType, IntegerType, StructField, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch

import scala.collection.mutable.ArrayBuffer

case class GpuRangePartitioning(gpuOrdering: Seq[GpuSortOrder], numPartitions: Int, part: GpuRangePartitioner)
  extends GpuExpression with GpuPartitioning {

  var rangeBounds: Array[InternalRow] = _
  var schema: StructType = new StructType()
  gpuOrdering.foreach { ord =>
    val sortOrder = ord.toSortOrder
    sortOrder.child.references.foreach(field => {
      schema = schema.add(StructField(field.name, field.dataType))
    })
  }

  override def children: Seq[GpuExpression] = gpuOrdering

  override def nullable: Boolean = false

  override def dataType: DataType = IntegerType

  override def satisfies0(required: Distribution): Boolean = {
    super.satisfies0(required) || {
      required match {
        case OrderedDistribution(requiredOrdering) =>
          // If `ordering` is a prefix of `requiredOrdering`:
          //   Let's say `ordering` is [a, b] and `requiredOrdering` is [a, b, c]. According to the
          //   RangePartitioning definition, any [a, b] in a previous partition must be smaller
          //   than any [a, b] in the following partition. This also means any [a, b, c] in a
          //   previous partition must be smaller than any [a, b, c] in the following partition.
          //   Thus `RangePartitioning(a, b)` satisfies `OrderedDistribution(a, b, c)`.
          //
          // If `requiredOrdering` is a prefix of `ordering`:
          //   Let's say `ordering` is [a, b, c] and `requiredOrdering` is [a, b]. According to the
          //   RangePartitioning definition, any [a, b, c] in a previous partition must be smaller
          //   than any [a, b, c] in the following partition. If there is a [a1, b1] from a previous
          //   partition which is larger than a [a2, b2] from the following partition, then there
          //   must be a [a1, b1 c1] larger than [a2, b2, c2], which violates RangePartitioning
          //   definition. So it's guaranteed that, any [a, b] in a previous partition must not be
          //   greater(i.e. smaller or equal to) than any [a, b] in the following partition. Thus
          //   `RangePartitioning(a, b, c)` satisfies `OrderedDistribution(a, b)`.
          val minSize = Seq(requiredOrdering.size, gpuOrdering.size).min
          requiredOrdering.take(minSize) == gpuOrdering.take(minSize)
        case ClusteredDistribution(requiredClustering, _) =>
          gpuOrdering.map(_.child).forall(x => requiredClustering.exists(_.semanticEquals(x)))
        case _ => false
      }
    }
  }

  override def columnarEval(batch: ColumnarBatch): Any = {
    var rangesBatch: ColumnarBatch = null
    var rangesTbl: Table = null
    var sortedTbl: Table = null
    var slicedSortedTbl: Table = null
    var retCv: ColumnVector = null
    var inputCvs: Seq[GpuColumnVector] = null
    var inputTbl: Table = null
    var partitionColumns: Array[GpuColumnVector] = null
    var parts: Array[Int] = Array(0)
    var slicedCb: Array[ColumnarBatch] = null

    val numSortCols = gpuOrdering.length
    val descFlags = new ArrayBuffer[Boolean]()
    val orderByArgs = gpuOrdering.zipWithIndex.map { case (order, index) =>
      if (order.isAscending) {
        descFlags += false
        Table.asc(index)
      } else {
        descFlags += true
        Table.desc(index)
      }
    }
    val nullFlags = new Array[Boolean](gpuOrdering.length)
    for (i <- gpuOrdering.indices) {
      nullFlags(i) = SortUtils.areNullsSmallest(gpuOrdering, i)
    }

    try {
      //get Inputs table bound
      inputCvs = SortUtils.getGpuColVectorsAndBindReferences(batch, gpuOrdering)
      inputTbl = new Table(inputCvs.map(_.getBase): _*)
      //get the ranges table
      rangesBatch = part.getRangesBatch(schema, part.rangeBounds)
      rangesTbl = GpuColumnVector.from(rangesBatch)
      //sort incoming batch to compare with ranges
      sortedTbl = inputTbl.orderBy(orderByArgs: _*)
      val columns = (0 until numSortCols).map(sortedTbl.getColumn(_))
      //get the table for upper bound calculation
      slicedSortedTbl = new Table(columns: _*)
      //get upper bounds
      retCv = slicedSortedTbl.upperBound(nullFlags, rangesTbl, descFlags.toArray)
      retCv.ensureOnHost()
      //partition indices based on upper bounds
      parts = parts ++ (0 until retCv.getRowCount.toInt).map(i => retCv.getInt(i)).toArray[Int]
      partitionColumns = GpuColumnVector.extractColumns(batch)
      slicedCb = sliceInternalGpuOrCpu(batch, parts, partitionColumns)
    } finally {
      if (inputCvs != null) {
        inputCvs.safeClose()
      }
      if (inputTbl != null) {
        inputTbl.close()
      }
      if (sortedTbl != null) {
        sortedTbl.close()
      }
      if (slicedSortedTbl != null) {
        slicedSortedTbl.close()
      }
      if (rangesBatch != null) {
        rangesBatch.close()
      }
      if (rangesTbl != null) {
        rangesTbl.close()
      }
      if (retCv != null) {
        retCv.close()
      }
      if (partitionColumns != null) {
        partitionColumns.safeClose()
      }
    }
    slicedCb.zipWithIndex.filter(_._1 != null)
  }
}