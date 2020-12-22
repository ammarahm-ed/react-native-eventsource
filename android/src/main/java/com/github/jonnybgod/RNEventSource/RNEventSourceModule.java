package com.github.jonnybgod.RNEventSource;
import androidx.annotation.WorkerThread;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Headers;
import okhttp3.FormBody;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.here.oksse.OkSse;
import com.here.oksse.ServerSentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.facebook.react.modules.core.DeviceEventManagerModule;


public class RNEventSourceModule extends ReactContextBaseJavaModule {
  private static ReactApplicationContext reactContext;
  private OkSse okSse;
  private HashMap<String, ServerSentEvent> sseManager;
  private int sseCount;
  final String sseRefID = "0";

  public RNEventSourceModule(ReactApplicationContext context) {
    super(context);
    reactContext = context;
    this.okSse = new OkSse();
    this.sseManager = new HashMap(100);
  }
 
  public String getName() {
    return "EventSource";
  }


  private HashMap<String, Object> recursivelyDeconstructReadableMap(ReadableMap readableMap) {
    ReadableMapKeySetIterator iterator = readableMap.keySetIterator();
    HashMap<String, Object> deconstructedMap = new HashMap<>();
    while (iterator.hasNextKey()) {
      String key = iterator.nextKey();
      ReadableType type = readableMap.getType(key);
      switch (type) {
        case Null:
          deconstructedMap.put(key, null);
          break;
        case Boolean:
          deconstructedMap.put(key, readableMap.getBoolean(key));
          break;
        case Number:
          deconstructedMap.put(key, readableMap.getDouble(key));
          break;
        case String:
          deconstructedMap.put(key, readableMap.getString(key));
          break;
        case Map:
          deconstructedMap.put(key, recursivelyDeconstructReadableMap(readableMap.getMap(key)));
          break;
        case Array:
          deconstructedMap.put(key, recursivelyDeconstructReadableArray(readableMap.getArray(key)));
          break;
        default:
          throw new IllegalArgumentException("Could not convert object with key: " + key + ".");
      }

    }
    return deconstructedMap;
  }

  private List<Object> recursivelyDeconstructReadableArray(ReadableArray readableArray) {
    List<Object> deconstructedList = new ArrayList<>(readableArray.size());
    for (int i = 0; i < readableArray.size(); i++) {
      ReadableType indexType = readableArray.getType(i);
      switch(indexType) {
        case Null:
          deconstructedList.add(i, null);
          break;
        case Boolean:
          deconstructedList.add(i, readableArray.getBoolean(i));
          break;
        case Number:
          deconstructedList.add(i, readableArray.getDouble(i));
          break;
        case String:
          deconstructedList.add(i, readableArray.getString(i));
          break;
        case Map:
          deconstructedList.add(i, recursivelyDeconstructReadableMap(readableArray.getMap(i)));
          break;
        case Array:
          deconstructedList.add(i, recursivelyDeconstructReadableArray(readableArray.getArray(i)));
          break;
        default:
          throw new IllegalArgumentException("Could not convert object at index " + i + ".");
      }
    }
    return deconstructedList;
  }


  // returns an SSE id which you need to refer back to for closing the connection
  // assumes that you're making a GET request, but you can also modify this to accept POST/PATCH requests as well
  @ReactMethod
  public void initRequest(
          String path,
          ReadableMap headersMap,
          Promise promise
  ) {
    Request.Builder requestBuilder = new Request.Builder().url(path);


    HashMap<String, Object> headersHashMap = recursivelyDeconstructReadableMap(headersMap);
    for (Map.Entry<String, Object> entry : headersHashMap.entrySet()) {
      String headerKey = entry.getKey();
      String headerValue = (String) entry.getValue(); // assumes that the key-value JSON passed into headers are <String, String>
      requestBuilder.addHeader(headerKey, headerValue);
    }

    final Request request = requestBuilder.build();

    ServerSentEvent sse = this.okSse.newServerSentEvent(request, new ServerSentEvent.Listener() {
      @Override
      public void onOpen(ServerSentEvent sse, Response response) {

        WritableMap map = new WritableNativeMap();
          if(!reactContext.hasActiveCatalystInstance()) {
            return;
        }

        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("open", map);
      }

      @Override
      public void onMessage(ServerSentEvent sse, String id, String event, String message) {
        // When a message is received
        WritableMap map = new WritableNativeMap();
        map.putString("id", id);
        map.putString("event", event);
        map.putString("message", message);

          if(!reactContext.hasActiveCatalystInstance()) {
            return;
        }

        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("message", map);
      }

      @WorkerThread
      @Override
      public void onComment(ServerSentEvent sse, String comment) {
        WritableMap map = new WritableNativeMap();
        map.putString("comment", comment);

          if(!reactContext.hasActiveCatalystInstance()) {
            return;
        }

        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("comment", map); // this identifies the HTTP connection and respective SSEs we want to be listening to on JS
      }

      @WorkerThread
      @Override
      public Request onPreRetry(ServerSentEvent sse, Request sseRequest) {
        return request;
      }

      @WorkerThread
      @Override
      public boolean onRetryTime(ServerSentEvent sse, long milliseconds) {
        return true; // True to use the new retry time received by SSE
      }

      @WorkerThread
      @Override
      public boolean onRetryError(ServerSentEvent sse, Throwable throwable, Response response) {
        return true; // True to retry, false otherwise
      }

      @WorkerThread
      @Override
      public void onClosed(ServerSentEvent sse) {
        // Channel closed
        sse.close();
      }
    });
    this.sseManager.put(sseRefID, sse);
    this.sseCount++;
    promise.resolve(sseRefID);
  }

  @ReactMethod
  public void close() {
    try {
      this.sseManager.get(sseRefID).close();
      this.sseManager.remove(sseRefID);
    } catch (Exception e) {
      System.out.println("failed to close connection");
    }
  }
}