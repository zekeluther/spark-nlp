package com.johnsnowlabs.ml.tensorflow

import com.johnsnowlabs.ml.tensorflow.sentencepiece._
import com.johnsnowlabs.nlp.annotators.common._

import scala.collection.JavaConverters._

class TensorflowXlnet(val tensorflow: TensorflowWrapper,
                      val spp: SentencePieceWrapper,
                      configProtoBytes: Option[Array[Byte]] = None
                     ) extends Serializable {

  // keys representing the input and output tensors of the ALBERT model
  private val tokenIdsKey = "input_ids"
  private val maskIdsKey = "input_mask"
  private val segmentIdsKey = "segment_ids"
  private val outputSequenceKey = "module/seq_out"

  private val tokenSEPCLSIds = Array(4, 3)

  def getSpecialTokens(token: String): Array[Int] = {
    spp.getSppModel.encodeAsIds(token)
  }

  def tag(batch: Seq[Array[Int]]): Seq[Array[Array[Float]]] = {

    val tensors = new TensorResources()
    val tensorsMasks = new TensorResources()
    val tensorsSegments = new TensorResources()

    /* Actual size of each sentence to skip padding in the TF model */
    val sequencesLength = batch.map(x => x.length).toArray
    val maxSentenceLength = sequencesLength.max

    val tokenBuffers = tensors.createIntBuffer(batch.length*maxSentenceLength)
    val maskBuffers = tensorsMasks.createFloatBuffer(batch.length*maxSentenceLength)
    val segmentBuffers = tensorsSegments.createIntBuffer(batch.length*maxSentenceLength)

    val shape = Array(batch.length.toLong, maxSentenceLength)

    batch.map { tokenIds =>
      val diff = maxSentenceLength - tokenIds.length
      segmentBuffers.put(Array.fill(maxSentenceLength-1)(0) ++ Array(2))

      if (tokenIds.length >= maxSentenceLength) {
        tokenBuffers.put(tokenIds)
        maskBuffers.put(tokenIds.map(x=> if (x == 0) 0.0f else 1.0f))
      }
      else {
        val newTokenIds = tokenIds ++ Array.fill(1, diff)(0).head
        tokenBuffers.put(newTokenIds)
        maskBuffers.put(newTokenIds.map(x=> if (x == 0) 0.0f else 1.0f))
      }
    }

    tokenBuffers.flip()
    maskBuffers.flip()
    segmentBuffers.flip()

    val tokenTensors = tensors.createIntBufferTensor(shape, tokenBuffers)
    val maskTensors = tensorsMasks.createFloatBufferTensor(shape, maskBuffers)
    val segmentTensors = tensorsSegments.createIntBufferTensor(shape, segmentBuffers)

    val runner = tensorflow.getTFHubSession(configProtoBytes = configProtoBytes).runner

    runner
      .feed(tokenIdsKey, tokenTensors)
      .feed(maskIdsKey, maskTensors)
      .feed(segmentIdsKey, segmentTensors)
      .fetch(outputSequenceKey)

    val outs = runner.run().asScala
    val embeddings = TensorResources.extractFloats(outs.head)

    tensors.clearSession(outs)
    tensors.clearTensors()
    tokenBuffers.clear()
    maskBuffers.clear()
    segmentBuffers.clear()

    val dim = embeddings.length / (batch.length * maxSentenceLength)
    val shrinkedEmbeddings: Array[Array[Array[Float]]] = embeddings.grouped(dim).toArray.grouped(maxSentenceLength).toArray

    val emptyVector = Array.fill(dim)(0f)

    batch.zip(shrinkedEmbeddings).map { case (ids, embeddings) =>
      if (ids.length > embeddings.length) {
        embeddings.take(embeddings.length - 1) ++
          Array.fill(embeddings.length - ids.length)(emptyVector) ++
          Array(embeddings.last)
      } else {
        embeddings
      }
    }

  }

  def calculateEmbeddings(sentences: Seq[TokenizedSentence],
                          poolingLayer: String,
                          batchSize: Int,
                          maxSentenceLength: Int,
                          dimension: Int,
                          caseSensitive: Boolean
                         ): Seq[WordpieceEmbeddingsSentence] = {

    sentences.grouped(batchSize).toArray.flatMap { batch =>

      val tokensPiece = tokenize(batch, maxSentenceLength, caseSensitive)
      val tokenIds = tokensPiece.map { sentence =>
        sentence.flatMap(x => x.tokens.map(x => x.pieceId)) ++ tokenSEPCLSIds
      }
      val vectors = tag(tokenIds)
      val tokenIdsVectors = tokenIds.zip(vectors).map { x =>
        x._1.zip(x._2).toMap
      }

      tokensPiece.zipWithIndex.zip(tokenIdsVectors).map { case (tokens, vectors) =>

        val tokensWithEmbeddings =  tokens._1.map{ token =>
          /* 17 is the id for '▁' token if appears alone */
          val subWord:TokenPiece = token.tokens.find(_.pieceId != 17).getOrElse(token.tokens.head)
          TokenPieceEmbeddings(
            subWord.wordpiece,
            subWord.token,
            subWord.pieceId,
            isWordStart = true,
            isOOV = false,
            vectors.apply(subWord.pieceId),
            subWord.begin,
            subWord.end
          )
        }
        WordpieceEmbeddingsSentence(tokensWithEmbeddings, tokens._2)
      }
    }

  }

  def tokenize(sentences: Seq[TokenizedSentence], maxSeqLength: Int, caseSensitive: Boolean):
  Seq[Array[WordpieceTokenizedSentence]] = {

    val sentecneTokenPieces = sentences.map { s =>
      // Account for one [SEP] & one [CLS]
      val shrinkedSentence = s.indexedTokens.take(maxSeqLength - 3)
      shrinkedSentence.map{
        case(token) =>
          val tokenContent = if (caseSensitive) token.token else token.token.toLowerCase()
          val tokenPieces = spp.getSppModel.encodeAsPieces(tokenContent).toArray.map(x=>x.toString)
          val tokenIds = spp.getSppModel.encodeAsIds(tokenContent)
          WordpieceTokenizedSentence(
            tokenPieces.zip(tokenIds).map(x=> TokenPiece(x._1, token.token, x._2, false, token.begin, token.end))
          )
      }
    }
    sentecneTokenPieces
  }

}