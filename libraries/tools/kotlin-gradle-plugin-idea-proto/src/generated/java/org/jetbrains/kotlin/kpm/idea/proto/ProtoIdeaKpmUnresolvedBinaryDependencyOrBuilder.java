// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: proto_idea_kpm.proto

package org.jetbrains.kotlin.kpm.idea.proto;

public interface ProtoIdeaKpmUnresolvedBinaryDependencyOrBuilder extends
    // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmUnresolvedBinaryDependency)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmExtras extras = 1;</code>
   * @return Whether the extras field is set.
   */
  boolean hasExtras();
  /**
   * <code>.org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmExtras extras = 1;</code>
   * @return The extras.
   */
  org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmExtras getExtras();
  /**
   * <code>.org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmExtras extras = 1;</code>
   */
  org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmExtrasOrBuilder getExtrasOrBuilder();

  /**
   * <code>optional .org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmBinaryCoordinates coordinates = 2;</code>
   * @return Whether the coordinates field is set.
   */
  boolean hasCoordinates();
  /**
   * <code>optional .org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmBinaryCoordinates coordinates = 2;</code>
   * @return The coordinates.
   */
  org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmBinaryCoordinates getCoordinates();
  /**
   * <code>optional .org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmBinaryCoordinates coordinates = 2;</code>
   */
  org.jetbrains.kotlin.kpm.idea.proto.ProtoIdeaKpmBinaryCoordinatesOrBuilder getCoordinatesOrBuilder();

  /**
   * <code>optional string cause = 3;</code>
   * @return Whether the cause field is set.
   */
  boolean hasCause();
  /**
   * <code>optional string cause = 3;</code>
   * @return The cause.
   */
  java.lang.String getCause();
  /**
   * <code>optional string cause = 3;</code>
   * @return The bytes for cause.
   */
  com.google.protobuf.ByteString
      getCauseBytes();
}
