package org.ncic.bioinfo.sparkseq.data.partition

import org.ncic.bioinfo.sparkseq.exceptions.PipelineException

/**
  * 每个fasta partition一定位于一个contig上，不存在跨contig的fasta partition。
  *
  * 由于overlap的存在，FastaPartition由4段构成
  * |---|-----|---------------------------------|-----|---|
  * A    B                   C                   D    E
  * 第C段是切分后的contig，所有的fasta partition连起来是密排且覆盖整个reference的
  * B D是overlap部分，也是每个partition需要处理的部分。
  * A E是safe overlap部分，在划分的时候，可能会需要reference两边多一个overlap。这部分不参与read的划分
  *
  * Author: wbc
  */
object FastaPartition {

  /**
    * 创建一个FastaPartition
    *
    * @param partitionId     partition Id，从0开始
    * @param contigId        contigId，从0开始
    * @param contigName      contig的名字
    * @param contigContent   fasta partition所在的contig的内容
    * @param startCoordinate C的头部在原reference上的位置
    * @param endCoordiname   C的尾部在原reference上的位置
    * @param overlapLen      B D段的长度
    * @param safeOverlapLen  A E端的长度
    * @return
    */
  def apply(partitionId: Int, contigId: Int, contigName: String,
            contigContent: String, startCoordinate: Int, endCoordinate: Int,
            overlapLen: Int, safeOverlapLen: Int): FastaPartition = {

    if (startCoordinate < 1 || startCoordinate > endCoordinate) {
      throw new PipelineException("Illegal fasta partition")
    }

    val contigStart = 1
    val contigEnd = contigContent.length

    val originStart = startCoordinate
    val overlappedStart = if (originStart - overlapLen >= contigStart) (originStart - overlapLen) else contigStart
    val safeOverlappedStart = if (overlappedStart - safeOverlapLen >= contigStart) (overlappedStart - safeOverlapLen) else contigStart

    val originEnd = endCoordinate
    val overlappedEnd = if (originEnd + overlapLen <= contigEnd) (originEnd + overlapLen) else contigEnd
    val safeOverlappedEnd = if (overlappedEnd + safeOverlapLen <= contigEnd) (overlappedEnd + safeOverlapLen) else contigEnd

    val content = contigContent.substring(safeOverlappedStart - 1, safeOverlappedEnd)
    new FastaPartition(partitionId, contigId, contigName, content,
      safeOverlappedStart, safeOverlappedEnd, overlappedStart, overlappedEnd, originStart, originEnd)
  }
}


class FastaPartition(partitionId: Int, val contigId: Int, val contigName: String,
                     val content: String,
                     val safeOverlappedStart: Int, val safeOverlappedEnd: Int,
                     val overlappedStart: Int, val overlappedEnd: Int,
                     val originStart: Int, val originEnd: Int)
  extends Partition(partitionId) {

}
