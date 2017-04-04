package org.ncic.bioinfo.sparkseq.processes.mapping

import org.ncic.bioinfo.sparkseq.algorithms.adapters.BwaMemAdapter
import org.ncic.bioinfo.sparkseq.const.{BinTools, SamRecordConst}
import org.ncic.bioinfo.sparkseq.data.bundle.{FASTQBundle, FASTQPairBundle, SAMBundle}
import org.ncic.bioinfo.sparkseq.data.common.{ReadGroupInfo, RefContigInfo}
import org.ncic.bioinfo.sparkseq.engine.AbstractProcess
import org.ncic.bioinfo.sparkseq.exceptions.{ResourceNotSetException, ResourceSetException}
import org.ncic.bioinfo.sparkseq.resource.Resource

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._

/**
  * Author: wbc
  */
object JNIBwaMemProcess {
  def singleEnd(name: String,
                refContigInfo: RefContigInfo,
                referencePath: String,
                fastqBundle: FASTQBundle,
                outputSamBundle: SAMBundle): JNIBwaMemProcess = {
    val process = new JNIBwaMemProcess(name, refContigInfo, referencePath,
      outputSamBundle, fastqBundle, null)
    process
  }

  def pairEnd(name: String,
              refContigInfo: RefContigInfo,
              referencePath: String,
              fastqPairBundle: FASTQPairBundle,
              outputSamBundle: SAMBundle): JNIBwaMemProcess = {
    val process = new JNIBwaMemProcess(name, refContigInfo, referencePath,
      outputSamBundle, null, fastqPairBundle)
    process
  }
}

class JNIBwaMemProcess(name: String,
                       refContigInfo: RefContigInfo,
                       referencePath: String,
                       outputSamBundle: SAMBundle,
                       fastqBundle: FASTQBundle,
                       fastqPairBundle: FASTQPairBundle) extends AbstractProcess(name) {

  override def getInputResourceList(): List[Resource] = {
    val resourceList = ListBuffer[Resource]()
    if (fastqBundle != null) {
      resourceList += fastqBundle
    }
    if (fastqPairBundle != null) {
      resourceList += fastqPairBundle
    }
    resourceList.toList
  }

  override def getOutputResourceList(): List[Resource] = List(outputSamBundle)

  private def runPair(): Unit = {
    // 输入
    val fastqPairRecordRdd = fastqPairBundle.fastqPairRecordRDD

    // 将readGroupInfo的信息加入arguments
    // 如果之前的sam header中不包含read group，则创建一个
    val readGroupInfo = {
      if (outputSamBundle.samHeaderInfo.getReadGroupInfos().isEmpty) {
        val readGroup = ReadGroupInfo("rg1", "sample1")
        outputSamBundle.samHeaderInfo.addReadGroupInfo(readGroup)
        readGroup
      } else {
        outputSamBundle.samHeaderInfo.getReadGroupInfos().head
      }
    }

    val bwaLibPath = BinTools.bwaLibPath
    val bwaLibPathBD = sc.broadcast(bwaLibPath).value
    val refContigInfoBD = sc.broadcast(refContigInfo).value
    val referencePathBD = sc.broadcast(referencePath).value
    val readGroupInfoBD = sc.broadcast(readGroupInfo).value
    val sampleNameBD = sc.broadcast(outputSamBundle.key).value
    val compressFlagBD = sc.broadcast(BinTools.shuffleCompress).value

    val samRDD = fastqPairRecordRdd.mapPartitions(recordIterator => {
      val samRecords = BwaMemAdapter.pairAlign(bwaLibPathBD, referencePathBD,
        readGroupInfoBD, refContigInfoBD, recordIterator.toSeq)
        .filter(record => record.contigId != SamRecordConst.FAKE_CONTIG_ID)
      if (compressFlagBD) {
        samRecords.map(record => record.compress).iterator
      } else {
        samRecords.iterator
      }
    })

    // set result
    outputSamBundle.samRecordRDD = samRDD
    outputSamBundle.setFlag = true
  }

  override def runProcess(): Unit = {

    if (fastqBundle != null && !fastqBundle.isSet) {
      throw new ResourceNotSetException(fastqBundle.key)
    }
    if (fastqPairBundle != null && !fastqPairBundle.isSet) {
      throw new ResourceNotSetException(fastqPairBundle.key)
    }
    if (outputSamBundle.isSet) {
      throw new ResourceSetException(outputSamBundle.key)
    }

    if (fastqBundle != null) {
      // run single
      throw new NotImplementedError()
    }
    if (fastqPairBundle != null) {
      runPair()
    }
  }
}
