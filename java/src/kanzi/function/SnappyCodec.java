/*
Copyright 2011, 2012 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.function;

import kanzi.ByteFunction;
import kanzi.IndexedByteArray;


// Snappy is a fast compression codec aiming for very high speed and
// reasonable compression ratios.
// This implementation is a port of the Go source at http://code.google.com/p/snappy-go/
public class SnappyCodec implements ByteFunction
{
   private static final int MAX_OFFSET     = 32768;
   private static final int TAG_LITERAL    = 0x00;
   private static final int TAG_COPY1      = 0x01;
   private static final int TAG_COPY2      = 0x02;
   private static final int TAG_DEC_LEN1   = 60;
   private static final int TAG_DEC_LEN2   = 61;
   private static final int TAG_DEC_LEN3   = 62;
   private static final int TAG_DEC_LEN4   = 63;
   private static final int TAG_ENC_LEN1   = (TAG_DEC_LEN1<<2) | TAG_LITERAL;
   private static final int TAG_ENC_LEN2   = (TAG_DEC_LEN2<<2) | TAG_LITERAL;
   private static final int TAG_ENC_LEN3   = (TAG_DEC_LEN3<<2) | TAG_LITERAL;
   private static final int TAG_ENG_LEN4   = (TAG_DEC_LEN4<<2) | TAG_LITERAL;
   private static final int MAX_TABLE_SIZE = 16384;
   private static final long HASH_SEED     = 0x1e35a7bd;

   private int size;
   private final int[] buffer;


   public SnappyCodec()
   {
      this(0);
   }


   public SnappyCodec(int size)
   {
      if (size < 0)
         throw new IllegalArgumentException("Invalid size parameter (must be at least 0)");

      this.size = size;
      this.buffer = new int[MAX_TABLE_SIZE];
   }


   public int size()
   {
      return this.size;
   }

   
   public boolean setSize(int sz)
   {
      if (size < 0)
         return false;
      
      this.size = sz;
      return true;
   }

   
   // emitLiteral writes a literal chunk and returns the number of bytes written.
   private static int emitLiteral(IndexedByteArray source, IndexedByteArray destination, int len)
   {
     int inc;
     int srcIdx = source.index;
     int dstIdx = destination.index;
     final byte[] src = source.array;
     final byte[] dst = destination.array;
     final int n = len - 1;

     if (n < 60)
     {
        inc = 1;
        dst[dstIdx] = (byte) ((n<<2) | TAG_LITERAL);
     }
     else if (n < 0x0100)
     {
        inc = 2;
        dst[dstIdx]   = (byte) TAG_ENC_LEN1;
        dst[dstIdx+1] = (byte) (n & 0xFF);
     }
     else if (n < 0x010000)
     {
        inc = 3;
        dst[dstIdx]   = (byte) TAG_ENC_LEN2;
        dst[dstIdx+1] = (byte) (n & 0xFF);
        dst[dstIdx+2] = (byte) ((n >> 8) & 0xFF);
     }
     else if (n < 0x01000000)
     {
        inc = 4;
        dst[dstIdx]   = (byte) TAG_ENC_LEN3;
        dst[dstIdx+1] = (byte) (n & 0xFF);
        dst[dstIdx+2] = (byte) ((n >> 8)  & 0xFF);
        dst[dstIdx+3] = (byte) ((n >> 16) & 0xFF);
     }
     else
     {
        inc = 5;
        dst[dstIdx]   = (byte) TAG_ENG_LEN4;
        dst[dstIdx+1] = (byte) (n & 0xFF);
        dst[dstIdx+2] = (byte) ((n >> 8)  & 0xFF);
        dst[dstIdx+3] = (byte) ((n >> 16) & 0xFF);
        dst[dstIdx+4] = (byte) ((n >> 24) & 0xFF);
     }

     dstIdx += inc;

     if (len < 16)
     {
	for (int i=0; i<len; i++)
	   dst[dstIdx++] = src[srcIdx++];
     }
     else
     {
        System.arraycopy(src, srcIdx, dst, dstIdx, len);
     }

     return inc + len;
  }


  // emitCopy writes a copy chunk and returns the number of bytes written.
  private static int emitCopy(IndexedByteArray destination, int offset, int len)
  {
     final byte[] dst = destination.array;
     int idx = destination.index;
     final byte b1 = (byte) offset;
     final byte b2 = (byte) (offset >> 8);

     while (len > 0)
     {
        if ((offset < 2048) && (len < 12) && (len >= 4))
        {
           dst[idx]   = (byte) (((b2 & 0x07)<<5) | ((len-4)<<2) | TAG_COPY1);
           dst[idx+1] = b1;
           idx += 2;
           break;
        }

        final int x = (len <= 64) ? len : 64;
        dst[idx]   = (byte) (((x-1)<<2) | TAG_COPY2);
        dst[idx+1] = b1;
        dst[idx+2] = b2;
        idx += 3;
        len -= x;
     }

     return idx - destination.index;
  }


  @Override
  public boolean forward(IndexedByteArray source, IndexedByteArray destination)
  {
     final int srcIdx = source.index;
     int dstIdx = destination.index;
     final byte[] src = source.array;
     final byte[] dst = destination.array;
     final int count = (this.size > 0) ? this.size : src.length - srcIdx;

     if (dst.length - dstIdx < getMaxEncodedLength(count))
        return false;

     // Create copies to manipulate the index without impacting parameters
     IndexedByteArray iba1 = new IndexedByteArray(src, srcIdx);
     IndexedByteArray iba2 = new IndexedByteArray(dst, dstIdx);

     // The block starts with the varint-encoded length of the decompressed bytes.
     int d = putUvarint(destination, (long) count);

     // Return early if src is short
     if (count <= 4)
     {
        if (count > 0)
        {
           iba2.index = d;
           d += emitLiteral(iba1, iba2, count);
        }

        source.index = srcIdx + count;
        destination.index = d;
        return true;
     }

     // Initialize the hash table. Its size ranges from 1<<8 to 1<<14 inclusive.
     int shift = 24;
     int tableSize = 256;
     final int[] table = this.buffer; // aliasing
     final int max = (count < MAX_TABLE_SIZE) ? count : MAX_TABLE_SIZE;

     while (tableSize < max)
     {
        shift--;
        tableSize <<= 1;
     }

     for (int i=0; i<tableSize; i++)
        table[i] = -1;

     // Iterate over the source bytes
     int s = srcIdx; // The iterator position
     int lit = srcIdx; // The start position of any pending literal bytes
     final int len = src.length;

     while (s+3 < len)
     {
        // Update the hash table
        long hl = (src[s] & 0xFF) | ((src[s+1] & 0xFF) << 8) |
                ((src[s+2] & 0xFF) << 16) | ((src[s+3] & 0xFF) << 24);
        hl = (((long) hl * HASH_SEED) & 0xFFFFFFFFL) >> shift;
        final int h = (int) hl;
        int t = table[h]; // The last position with the same hash as s
        table[h] = s;

        // If t is invalid or src[s:s+4] differs from src[t:t+4], accumulate a literal byte
        if ((t < 0) || (s-t >= MAX_OFFSET))
        {
           s++;
           continue;
        }
        
        if ((src[s] != src[t]) || (src[s+1] != src[t+1]) || 
            (src[s+2] != src[t+2]) || (src[s+3] != src[t+3]))
        {
           s++;
           continue;
        }

        // Otherwise, we have a match. First, emit any pending literal bytes
        if (lit != s)
        {
           iba1.index = lit;
           iba2.index = d;
           d += emitLiteral(iba1, iba2, s-lit);
        }

        // Extend the match to be as long as possible
        final int s0 = s;
        s += 4;
        t += 4;

        while ((s < len) && (src[s] == src[t]))
        {
           s++;
           t++;
        }

        // Emit the copied bytes
        iba2.index = d;
        d += emitCopy(iba2, s-t, s-s0);
        lit = s;
     }

     // Emit any final pending literal bytes and return
     if (lit != len)
     {
        iba1.index = lit;
        iba2.index = d;
        d += emitLiteral(iba1, iba2, len-lit);
     }

     source.index = srcIdx + count;
     destination.index = d;
     return true;
  }


  private static int putUvarint(IndexedByteArray iba, long x)
  {
     int idx = iba.index;
     final byte[] array = iba.array;

     for ( ; x >= 0x80; x>>=7)
        array[idx++] = (byte) (x | 0x80);

     array[idx++] = (byte) x;
     return idx;
  }

  
  // Uvarint decodes a long from buf and returns that value.
  // If an error occurred, an exception is raised.
  // The index of the indexed byte array is incremented by the number
  // of bytes read
  private static long getUvarint(IndexedByteArray iba) 
  {
     final int idx = iba.index;
     final byte[] array = iba.array;
     final int len = array.length;
     long res = 0;
     int s = 0;
 
     for (int i=idx; i<len; i++)
     {
        final int b = array[i] & 0xFF;
        
        if (b < 0x80)
        {
           if ((i-idx > 9) || ((b > 1) && (i-idx == 9)))
              throw new NumberFormatException("Overflow: value is larger than 64 bits");
        
           iba.index = i + 1;
           return res | (((long) (b&0xFF)) << s);        
        }
        
        res |= (((long) (b&0x7F)) << s);
        s += 7;
     }
     
     throw new IllegalArgumentException("Input buffer too small");
  }

  // getMaxEncodedLength returns the maximum length of a snappy block, given its
  // uncompressed length.
  //
  // Compressed data can be defined as:
  //    compressed := item* literal*
  //    item       := literal* copy
  //
  // The trailing literal sequence has a space blowup of at most 62/60
  // since a literal of length 60 needs one tag byte + one extra byte
  // for length information.
  //
  // Item blowup is trickier to measure. Suppose the "copy" op copies
  // 4 bytes of data. Because of a special check in the encoding code,
  // we produce a 4-byte copy only if the offset is < 65536. Therefore
  // the copy op takes 3 bytes to encode, and this type of item leads
  // to at most the 62/60 blowup for representing literals.
  //
  // Suppose the "copy" op copies 5 bytes of data. If the offset is big
  // enough, it will take 5 bytes to encode the copy op. Therefore the
  // worst case here is a one-byte literal followed by a five-byte copy.
  // That is, 6 bytes of input turn into 7 bytes of "compressed" data.
  //
  // This last factor dominates the blowup, so the final estimate is:
  public static int getMaxEncodedLength(int srcLen)
  {
     return 32 + srcLen + srcLen/6;
  }


  // getDecodedLength returns the length of the decoded block or -1 if error
  // The index of the indexed byte array is incremented by the number
  // of bytes read
  public static int getDecodedLength(IndexedByteArray source) 
  {
     try
     {
        final long v = getUvarint(source);
        
        if (v > 0x7FFFFFFFL)
           return -1;
        
        return (int) (v);
     }
     catch (NumberFormatException e)
     {
        return -1;
     }
     catch (IllegalArgumentException e)
     {
        return -1;
     }
  }    
  

  @Override
  public boolean inverse(IndexedByteArray source, IndexedByteArray destination)
  {
     final int srcIdx = source.index;
     final int dstIdx = destination.index;
     final byte[] src = source.array;
     final byte[] dst = destination.array;
     
     // Get decoded length, modify source index
     final int dLen = getDecodedLength(source);
    
     if ((dLen < 0) || (dst.length - dstIdx < dLen)) 
        return false;
     
     final int count = (this.size > 0) ? this.size : src.length - srcIdx;
     int s = source.index;
     int d = dstIdx;
     int offset = 0;
     int length = 0;

     while (s < count) 
     {       
        switch (src[s] & 0x03)
        {
           case TAG_LITERAL:
           {
              int x = (src[s] & 0xFF) >> 2;
              
              if (x < TAG_DEC_LEN1)
                  s++;
              else if (x == TAG_DEC_LEN1)
              {
                 s += 2;
                  
                 if (s > count)
                    return false;
                  
                 x = src[s-1] & 0xFF;
              }
              else if (x == TAG_DEC_LEN2)
              {
                 s += 3;

                 if (s > count)
                    return false;
                  
                 x = (src[s-2] & 0xFF) | ((src[s-1] & 0xFF) << 8);
              }
              else if (x == TAG_DEC_LEN3)
              {
                 s += 4;

                 if (s > count)
                    return false;
                  
                 x = (src[s-3] & 0xFF) | ((src[s-2] & 0xFF) << 8) | 
                     ((src[s-1]) & 0xFF) << 16;
              }
              else if (x == TAG_DEC_LEN4)
              {
                 s += 5;

                 if (s > count)
                    return false;

                 x = (src[s-4] & 0xFF) | ((src[s-3] & 0xFF) << 8) |
                     ((src[s-2] & 0xFF) << 16) | ((src[s-1] & 0xFF) << 24);
              }   
                 
              length = x + 1;
                
              if ((length <= 0) || (length > dst.length-d) || (length > count-s))
                 return false;

              if (length < 16)
              {
                 for (int i=0; i<length; i++)
                    dst[d++] = src[s++]; 
              }
              else
              {
                 System.arraycopy(src, s, dst, d, length);
                 d += length;
                 s += length;
              }
              
              continue;
           }

           case TAG_COPY1:
           {
              s += 2;

              if (s > count)
                 return false;

              length = 4 + (((src[s-2] & 0xFF) >> 2) & 0x07);
              offset = ((src[s-2] & 0xE0) << 3) | (src[s-1] & 0xFF);
              break;
           }
               
           case TAG_COPY2:
           {
              s += 3;
               
              if (s > count) 
                 return false;

              length = 1 + ((src[s-3] & 0xFF) >> 2);
              offset = (src[s-2] & 0xFF) | ((src[s-1] & 0xFF) << 8);
              break;
           }
        }
          
        final int end = d + length;
            
        if ((offset > d) || (end > dst.length))
           return false;

        for ( ; d<end; d++)
           dst[d] = dst[d-offset];
     }

     if (d - dstIdx != dLen)
        return false;
       
     source.index = srcIdx + count;
     destination.index = d;
     return true;
  }
}