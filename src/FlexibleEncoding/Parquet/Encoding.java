package FlexibleEncoding.Parquet;
/*
 * adapted  from Parquet*
 */


//import static parquet.column.values.bitpacking.Packer.BIG_ENDIAN;
//import static parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
//import static parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
//import static parquet.schema.PrimitiveType.PrimitiveTypeName.BOOLEAN;

import java.io.IOException;

//import parquet.io.ParquetDecodingException;
/**
 * encoding of the data
 *
 * @author Julien Le Dem
 *
 */
public enum Encoding {

  PLAIN {
    @Override
    public ValuesReader getValuesReader(ColumnDescriptor descriptor, ValuesType valuesType) {
      switch (descriptor.getType()) {
      case BOOLEAN:
        return new BooleanPlainValuesReader();
      case BINARY:
        return new BinaryPlainValuesReader();
      case FLOAT:
        return new PlainValuesReader.FloatPlainValuesReader();
      case DOUBLE:
        return new PlainValuesReader.DoublePlainValuesReader();
      case INT32:
        return new PlainValuesReader.IntegerPlainValuesReader();
      case INT64:
        return new PlainValuesReader.LongPlainValuesReader();
      case FIXED_LEN_BYTE_ARRAY:
        return new FixedLenByteArrayPlainValuesReader(descriptor.getTypeLength());
      default:
        throw new ParquetDecodingException("no plain reader for type " + descriptor.getType());
      }
    }
  },

  /**
   * Actually a combination of bit packing and run length encoding.
   * TODO: Should we rename this to be more clear?
   */
  RLE {
    @Override
    public ValuesReader getValuesReader(ColumnDescriptor descriptor, ValuesType valuesType) {
      int bitWidth = BytesUtils.getWidthFromMaxInt(getMaxLevel(descriptor, valuesType));
      if(bitWidth == 0) {
        return new ZeroIntegerValuesReader();
      }
      return new RunLengthBitPackingHybridValuesReader(bitWidth);
    }
  },

  /**
   * This is no longer used, and has been replaced by {@link #RLE}
   * which is combination of bit packing and rle
   */
  @Deprecated
  BIT_PACKED {
    @Override
    public ValuesReader getValuesReader(ColumnDescriptor descriptor, ValuesType valuesType) {
      return new ByteBitPackingValuesReader(getMaxLevel(descriptor, valuesType), Packer.BIG_ENDIAN);
    }
  },

  PLAIN_DICTIONARY {
    @Override
    public ValuesReader getDictionaryBasedValuesReader(ColumnDescriptor descriptor, ValuesType valuesType, Dictionary dictionary) {
      switch (descriptor.getType()) {
      case BINARY:
      case INT64:
      case DOUBLE:
      case INT32:
      case FLOAT:
        return new DictionaryValuesReader(dictionary);
      default:
        throw new ParquetDecodingException("Dictionary encoding not supported for type: " + descriptor.getType());
      }
    }

    @Override
    public Dictionary initDictionary(ColumnDescriptor descriptor, DictionaryPage dictionaryPage) throws IOException {
      switch (descriptor.getType()) {
      case BINARY:
        return new PlainValuesDictionary.PlainBinaryDictionary(dictionaryPage);
      case INT64:
        return new PlainValuesDictionary.PlainLongDictionary(dictionaryPage);
      case DOUBLE:
        return new PlainValuesDictionary.PlainDoubleDictionary(dictionaryPage);
      case INT32:
        return new PlainValuesDictionary.PlainIntegerDictionary(dictionaryPage);
      case FLOAT:
        return new PlainValuesDictionary.PlainFloatDictionary(dictionaryPage);
      default:
        throw new ParquetDecodingException("Dictionary encoding not supported for type: " + descriptor.getType());
      }

    }
    @Override
   public boolean usesDictionary() {
     return true;
   }
 },

 /**
   * Delta encoding for integers. This can be used for int columns and works best
   * on sorted data
   */
  DELTA_BINARY_PACKED {
    @Override
    public ValuesReader getValuesReader(ColumnDescriptor descriptor, ValuesType valuesType) {
      if(descriptor.getType() != PrimitiveType.PrimitiveTypeName.INT32) {
        throw new ParquetDecodingException("Encoding DELTA_BINARY_PACKED is only supported for type INT32");
      }
      return new DeltaBinaryPackingValuesReader();
    }
  },

  /**
   * Encoding for byte arrays to separate the length values and the data. The lengths
   * are encoded using DELTA_BINARY_PACKED
   */
  DELTA_LENGTH_BYTE_ARRAY {
    @Override
    public ValuesReader getValuesReader(ColumnDescriptor descriptor,
        ValuesType valuesType) {
      if (descriptor.getType() != PrimitiveType.PrimitiveTypeName.BINARY) {
        throw new ParquetDecodingException("Encoding DELTA_LENGTH_BYTE_ARRAY is only supported for type BINARY");
      }
      return new DeltaLengthByteArrayValuesReader();
    }
  },

  /**
   * Incremental-encoded byte array. Prefix lengths are encoded using DELTA_BINARY_PACKED.
   * Suffixes are stored as delta length byte arrays.
   */
  DELTA_BYTE_ARRAY {
 
    public ValuesReader getValuesReader(ColumnDescriptor descriptor,
        ValuesType valuesType) {
      if (descriptor.getType() != PrimitiveType.PrimitiveTypeName.BINARY) {
        throw new ParquetDecodingException("Encoding DELTA_BYTE_ARRAY is only supported for type BINARY");
      }
      return new DeltaByteArrayReader();
    }
  },

  /**
   * Dictionary encoding: the ids are encoded using the RLE encoding
   */
  RLE_DICTIONARY;

  int getMaxLevel(ColumnDescriptor descriptor, ValuesType valuesType) {
    int maxLevel;
    switch (valuesType) {
    case REPETITION_LEVEL:
      maxLevel = descriptor.getMaxRepetitionLevel();
      break;
    case DEFINITION_LEVEL:
      maxLevel = descriptor.getMaxDefinitionLevel();
      break;
    case VALUES:
      if(descriptor.getType() == PrimitiveType.PrimitiveTypeName.BOOLEAN) {
        maxLevel = 1;
        break;
      }
    default:
      throw new ParquetDecodingException("Unsupported encoding for values: " + this);
    }
    return maxLevel;
  }

  /**
   * @return whether this encoding requires a dictionary
   */
  public boolean usesDictionary() {
    return false;
  }

  /**
   * initializes a dictionary from a page
   * @param dictionaryPage
   * @return the corresponding dictionary
   */
  public Dictionary initDictionary(ColumnDescriptor descriptor, DictionaryPage dictionaryPage) throws IOException {
    throw new UnsupportedOperationException(this.name() + " does not support dictionary");
  }

  /**
   * To read decoded values that don't require a dictionary
   *
   * @param descriptor the column to read
   * @param valuesType the type of values
   * @return the proper values reader for the given column
   * @throw {@link UnsupportedOperationException} if the encoding is dictionary based
   */
  public ValuesReader getValuesReader(ColumnDescriptor descriptor, ValuesType valuesType) {
    throw new UnsupportedOperationException("Error decoding " + descriptor + ". " + this.name() + " is dictionary based");
  }

  /**
   * To read decoded values that require a dictionary
   *
   * @param descriptor the column to read
   * @param valuesType the type of values
   * @param dictionary the dictionary
   * @return the proper values reader for the given column
   * @throw {@link UnsupportedOperationException} if the encoding is not dictionary based
   */
  public ValuesReader getDictionaryBasedValuesReader(ColumnDescriptor descriptor, ValuesType valuesType, Dictionary dictionary) {
    throw new UnsupportedOperationException(this.name() + " is not dictionary based");
  }

}
